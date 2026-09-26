/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.quickactions.island.media.domain.interactor

import android.app.PendingIntent
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.remedia.data.model.MediaDataModel
import com.android.systemui.media.remedia.data.repository.MediaRepositoryImpl
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.StatusBarLyricFetcher
import com.android.systemui.statusbar.quickactions.island.media.shared.model.LyricLine
import com.android.systemui.statusbar.quickactions.island.media.shared.model.LyricWord
import com.android.systemui.statusbar.quickactions.island.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.LYRICS
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.MEDIA_CONTROLS
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import android.provider.Settings

private const val PLAYBACK_POSITION_POLL_INTERVAL_MS = 1000L
private const val LYRIC_FETCH_RETRY_DELAY_MS = 15_000L
private const val MAX_LYRIC_FETCH_RETRIES = 2

/**
 * Interactor for managing the state of the media control chip in the status bar.
 *
 * Provides a [StateFlow] of [MediaControlChipModel] representing the current state of the media
 * control chip. Emits a new [MediaControlChipModel] when there is an active media session and the
 * corresponding user preference is found, otherwise emits null.
 */
@SysUISingleton
class MediaControlChipInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Background private val backgroundScope: CoroutineScope,
    private val mediaRepository: MediaRepositoryImpl,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
    private val axDynamicBarSettings: AxDynamicBarSettings,
) {
    private val isEnabled = MutableStateFlow(false)
    private val isDynamicIslandEnabled = MutableStateFlow(false)
    private var isInitialized = false
    private val dynamicIslandObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateDynamicIslandState()
            }
        }

    private val mediaControlChipModelForScene: Flow<MediaControlState> = snapshotFlow {
        val currentMedia = mediaRepository.currentMedia
        // Playback can be running before the pipeline marks the entry active. Prefer that
        // session so the island does not wait out a cancelled notification reload.
        val playing =
            currentMedia.firstOrNull {
                it.state is MediaSessionState.Playing || it.state is MediaSessionState.Buffering
            }
        (playing ?: currentMedia.firstOrNull { it.isActive })?.toMediaControlState(
            context = context,
            activityStarter = activityStarter,
            activityIntentHelper = activityIntentHelper,
            lockscreenUserManager = lockscreenUserManager,
            keyguardStateController = keyguardStateController,
        )
            ?: MediaControlState.Hidden
    }

    /**
     * A flow of [MediaControlChipModel] representing the current state of the media controls chip.
     * This flow emits null when no active media is playing or when playback information is
     * unavailable. This flow is only active when [MediaControlsInComposeFlag] is disabled.
     */
    private val mediaControlChipModelLegacy = MutableStateFlow(MediaControlState.Hidden)

    fun updateMediaControlChipModelLegacy(mediaData: MediaData?) {
        if (!MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelLegacy.value =
                mediaData?.toMediaControlState(
                    context = context,
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                ) ?: MediaControlState.Hidden
        }
    }

    private val mediaControlState: Flow<MediaControlState> =
        if (MediaControlsInComposeFlag.isEnabled) {
            mediaControlChipModelForScene
        } else {
            mediaControlChipModelLegacy
        }

    private val livePlaybackInfo: Flow<PlaybackInfo?> =
        mediaControlState
            .flatMapLatest { state -> state.token.playbackInfoFlow(context) }
            .distinctUntilChanged()

    private val axMediaActive: Flow<Boolean> =
        combine(
            axDynamicBarSettings.isEnabled,
            axDynamicBarSettings.disabledEventTypes,
            axDynamicBarSettings.isLockscreenMediaEnabled,
            axDynamicBarSettings.isLockscreenMediaLyricsEnabled,
        ) { barEnabled, disabledEvents, lockscreenMedia, lockscreenLyrics ->
            (barEnabled && "media" !in disabledEvents) || lockscreenMedia || lockscreenLyrics
        }

    private val showMediaControls: Flow<Boolean> =
        combine(
            isDynamicIslandEnabled,
            observeDynamicIslandFeatureEnabled(context, MEDIA_CONTROLS),
            axMediaActive,
        ) { islandEnabled, mediaControlsEnabled, axMediaActive ->
            (islandEnabled && mediaControlsEnabled) || axMediaActive
        }

    private val currentLyrics = MutableStateFlow<String?>(null)
    private val currentSyncedLyrics = MutableStateFlow<String?>(null)
    private val currentTimedLyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    private val islandLyricsEnabled =
        observeDynamicIslandFeatureEnabled(context, LYRICS, defaultValue = false)
    private var lyricsFetchJob: Job? = null
    private val lyricsFetchGeneration = AtomicInteger(0)
    private var lastFetchedKey: String? = null
    private var cachedTrackIdentity: String? = null
    private var cachedTrack: LyricTrack? = null

    private val baseMediaControlChipModel: Flow<MediaControlChipModel?> =
        combine(mediaControlState, livePlaybackInfo, isEnabled, showMediaControls) {
            mediaControlState,
            playbackInfo,
            isEnabled,
            showMediaControls ->
                if (isEnabled && showMediaControls) {
                    mediaControlState.model?.withPlaybackInfo(playbackInfo)
                } else {
                    null
                }
            }

    /** The currently active [MediaControlChipModel] */
    val mediaControlChipModel: StateFlow<MediaControlChipModel?> =
        combine(
            baseMediaControlChipModel,
            currentLyrics,
            currentSyncedLyrics,
            currentTimedLyrics,
            islandLyricsEnabled,
        ) { baseModel, lyrics, syncedLyrics, timedLyrics, isIslandLyricsEnabled ->
            baseModel?.copy(
                lyrics = lyrics,
                syncedLyrics = syncedLyrics,
                timedLyrics = timedLyrics,
                isDynamicIslandLyricsEnabled = isIslandLyricsEnabled,
            )
        }
        .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), null)

    /** Initializes setting observation. This must be called from a CoreStartable. */
    fun initialize() {
        if (isInitialized) {
            return
        }
        isInitialized = true
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(
                Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND
            ),
            false,
            dynamicIslandObserver,
            UserHandle.USER_ALL
        )
        updateDynamicIslandState()
        isEnabled.value = true
        setupLyricsWorker()
    }

    private fun updateDynamicIslandState() {
        isDynamicIslandEnabled.value =
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
                0,
                UserHandle.USER_CURRENT
            ) != 0
    }

    private fun setupLyricsWorker() {
        backgroundScope.launch {
            val dynamicBarLyricsEnabled =
                combine(
                    axDynamicBarSettings.isEnabled,
                    axDynamicBarSettings.disabledEventTypes,
                    axDynamicBarSettings.isLockscreenMediaLyricsEnabled,
                ) { isEnabled, disabledEvents, lockscreenLyricsEnabled ->
                    val barLyricsEnabled =
                        isEnabled && "media" !in disabledEvents && "lyrics" !in disabledEvents
                    barLyricsEnabled || lockscreenLyricsEnabled
                }
            combine(
                mediaControlState,
                islandLyricsEnabled,
                dynamicBarLyricsEnabled,
                lyricSourceChanges(),
            ) { state, islandLyrics, barLyrics, _ ->
                state to (islandLyrics || barLyrics)
            }
                .collect { (state, lyricsEnabled) ->
                    val track = if (lyricsEnabled) cachedLyricTrack(state) else null
                    if (track == null) {
                        clearFetchedLyrics()
                        return@collect
                    }
                    val userId = lockscreenUserManager.currentUserId
                    val sourceSetting =
                        Settings.Secure.getStringForUser(
                            context.contentResolver,
                            Settings.Secure.STATUS_BAR_LYRIC_SOURCES,
                            userId,
                        )
                    val wordTiming =
                        Settings.Secure.getIntForUser(
                            context.contentResolver,
                            Settings.Secure.STATUS_BAR_LYRIC_WORD_TIMING,
                            1,
                            userId,
                        )
                    val key =
                        listOf(
                            track.packageName,
                            track.mediaId,
                            track.title,
                            track.artist,
                            track.album,
                            track.durationMs,
                            sourceSetting,
                            wordTiming,
                        ).joinToString("\u0000")
                    if (key == lastFetchedKey) {
                        return@collect
                    }
                    lyricsFetchJob?.cancel()
                    val generation = lyricsFetchGeneration.incrementAndGet()
                    lastFetchedKey = key
                    currentLyrics.value = null
                    currentSyncedLyrics.value = null
                    currentTimedLyrics.value = emptyList()
                    lyricsFetchJob =
                        backgroundScope.launch {
                            var result: StatusBarLyricFetcher.Result? = null
                            var attempt = 0
                            while (isActive && generation == lyricsFetchGeneration.get()) {
                                result =
                                    runInterruptible {
                                        StatusBarLyricFetcher.fetch(
                                            context,
                                            userId,
                                            track.packageName,
                                            track.mediaId,
                                            track.title,
                                            track.artist,
                                            track.album,
                                            track.durationMs,
                                        )
                                    }
                                if (result != null || attempt >= MAX_LYRIC_FETCH_RETRIES) {
                                    break
                                }
                                attempt++
                                delay(LYRIC_FETCH_RETRY_DELAY_MS)
                            }
                            if (generation != lyricsFetchGeneration.get()) {
                                return@launch
                            }
                            currentLyrics.value = result?.plainLyrics
                            currentSyncedLyrics.value = result?.syncedLyrics
                            currentTimedLyrics.value = result?.toTimedLines().orEmpty()
                        }
                }
        }
    }

    private fun clearFetchedLyrics() {
        lyricsFetchJob?.cancel()
        lyricsFetchJob = null
        lyricsFetchGeneration.incrementAndGet()
        lastFetchedKey = null
        cachedTrackIdentity = null
        cachedTrack = null
        currentLyrics.value = null
        currentSyncedLyrics.value = null
        currentTimedLyrics.value = emptyList()
    }

    private fun StatusBarLyricFetcher.Result.toTimedLines(): List<LyricLine> {
        return lines.map { line ->
            LyricLine(
                timestampMs = line.timestampMs,
                text = line.text,
                words =
                    line.words.map { word ->
                        LyricWord(
                            beginMs = word.beginMs,
                            endMs = word.endMs,
                            text = word.text,
                        )
                    },
            )
        }
    }

    private fun lyricSourceChanges(): Flow<Unit> = callbackFlow {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_LYRIC_SOURCES),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_LYRIC_WORD_TIMING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun cachedLyricTrack(state: MediaControlState): LyricTrack? {
        val model = state.model ?: return null
        val identity =
            listOf(
                model.packageName,
                model.songName,
                model.artistName,
                model.durationMs,
                state.token,
            ).joinToString("\u0000")
        if (identity == cachedTrackIdentity) {
            return cachedTrack
        }
        val track = lyricTrackFor(state)
        cachedTrackIdentity = identity
        cachedTrack = track
        return track
    }

    private fun lyricTrackFor(state: MediaControlState): LyricTrack? {
        val model = state.model ?: return null
        val token = state.token
        if (token != null) {
            val metadata =
                try {
                    val controller = MediaController(context, token)
                    controller.metadata?.let { it to (controller.packageName ?: model.packageName) }
                } catch (_: RuntimeException) {
                    null
                }
            if (metadata != null) {
                val (tags, packageName) = metadata
                var title = tags.getString(MediaMetadata.METADATA_KEY_TITLE)
                if (title.isNullOrEmpty()) {
                    title = tags.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                }
                if (!title.isNullOrEmpty()) {
                    var artist = tags.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    if (artist.isNullOrEmpty()) {
                        artist = tags.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    }
                    val durationMs =
                        if (tags.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                            tags.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        } else {
                            model.durationMs
                        }
                    return LyricTrack(
                        packageName = packageName,
                        mediaId = tags.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                        title = title,
                        artist = artist,
                        album = tags.getString(MediaMetadata.METADATA_KEY_ALBUM),
                        durationMs = durationMs,
                    )
                }
            }
        }
        val title = model.songName?.toString()
        if (title.isNullOrBlank()) {
            return null
        }
        return LyricTrack(
            packageName = model.packageName,
            mediaId = null,
            title = title,
            artist = model.artistName?.toString(),
            album = null,
            durationMs = model.durationMs,
        )
    }
}

private fun MediaDataModel.toMediaControlState(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): MediaControlState {
    return MediaControlState(
        model =
            MediaControlChipModel(
                appIcon = appIcon,
                artworkIcon = background ?: appIcon,
                appName = appName,
                artistName = subtitle,
                songName = title,
                playOrPause = playbackStateActions?.getActionById(R.id.actionPlayPause),
                nextAction = playbackStateActions?.getActionById(R.id.actionNext),
                previousAction = playbackStateActions?.getActionById(R.id.actionPrev),
                customAction0 = playbackStateActions?.custom0,
                customAction1 = playbackStateActions?.custom1,
                openApp =
                    clickIntent.toAction(
                        activityStarter = activityStarter,
                        activityIntentHelper = activityIntentHelper,
                        lockscreenUserManager = lockscreenUserManager,
                        keyguardStateController = keyguardStateController,
                    ),
                seekTo = token.toSeekAction(context),
                durationMs = durationMs,
                positionMs = positionMs,
                canBeScrubbed = canBeScrubbed,
                isPlaying = state is MediaSessionState.Playing || state is MediaSessionState.Buffering,
                packageName = packageName,
            ),
        token = token,
    )
}

private fun MediaData.toMediaControlState(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): MediaControlState {
    val contentDescription = app?.let { ContentDescription.Loaded(it) }
    return MediaControlState(
        model =
            MediaControlChipModel(
                appIcon = appIcon.loadUiIcon(context, contentDescription),
                artworkIcon = artwork.loadUiIcon(context, contentDescription),
                appName = app,
                artistName = artist,
                songName = song,
                playOrPause = semanticActions?.getActionById(R.id.actionPlayPause),
                nextAction = semanticActions?.getActionById(R.id.actionNext),
                previousAction = semanticActions?.getActionById(R.id.actionPrev),
                customAction0 = semanticActions?.custom0,
                customAction1 = semanticActions?.custom1,
                openApp =
                    clickIntent.toAction(
                        activityStarter = activityStarter,
                        activityIntentHelper = activityIntentHelper,
                        lockscreenUserManager = lockscreenUserManager,
                        keyguardStateController = keyguardStateController,
                    ),
                seekTo = token.toSeekAction(context),
                durationMs = 0L,
                positionMs = 0L,
                canBeScrubbed = false,
                isPlaying = isPlaying ?: playOrPauseLooksPlaying(),
                packageName = packageName,
            ),
        token = token,
    )
}

private fun MediaData.playOrPauseLooksPlaying(): Boolean {
    val description = semanticActions?.getActionById(R.id.actionPlayPause)?.contentDescription
    return description?.toString()?.lowercase()?.contains("pause") == true
}

private fun android.graphics.drawable.Icon?.loadUiIcon(
    context: Context,
    contentDescription: ContentDescription?,
): UiIcon? {
    return this?.loadDrawable(context)?.let { UiIcon.Loaded(it, contentDescription) }
}

private fun PendingIntent?.toAction(
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): (() -> Unit)? {
    return this?.let { pendingIntent ->
        {
            val showOverLockscreen =
                keyguardStateController.isShowing &&
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        pendingIntent,
                        lockscreenUserManager.currentUserId,
                    )

            if (showOverLockscreen) {
                activityStarter.startPendingIntentMaybeDismissingKeyguard(
                    pendingIntent,
                    null,
                    null,
                )
            } else {
                activityStarter.postStartActivityDismissingKeyguard(pendingIntent, null)
            }
        }
    }
}

private fun MediaSession.Token?.toSeekAction(context: Context): ((Long) -> Unit)? {
    return this?.let { token ->
        { targetPositionMs ->
            runCatching {
                MediaController(context, token)
                    .transportControls
                    .seekTo(targetPositionMs.coerceAtLeast(0L))
            }
        }
    }
}

private fun MediaSession.Token?.playbackInfoFlow(context: Context): Flow<PlaybackInfo?> {
    if (this == null) {
        return flowOf(null)
    }
    return callbackFlow {
        val controller =
            runCatching { MediaController(context, this@playbackInfoFlow) }.getOrNull()
                ?: run {
                    trySend(null)
                    close()
                    return@callbackFlow
                }
        var poller: Job? = null

        fun updatePolling(playbackInfo: PlaybackInfo) {
            if (playbackInfo.isPlaying) {
                if (poller?.isActive == true) {
                    return
                }
                poller =
                    launch {
                        while (isActive) {
                            delay(PLAYBACK_POSITION_POLL_INTERVAL_MS)
                            trySend(controller.resolvePlaybackInfo(context))
                        }
                    }
            } else {
                poller?.cancel()
                poller = null
            }
        }

        fun dispatchPlaybackInfo() {
            val playbackInfo = controller.resolvePlaybackInfo(context)
            trySend(playbackInfo)
            updatePolling(playbackInfo)
        }

        val callback =
            object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    dispatchPlaybackInfo()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    dispatchPlaybackInfo()
                }

                override fun onSessionDestroyed() {
                    trySend(null)
                    close()
                }
            }

        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        dispatchPlaybackInfo()

        awaitClose {
            poller?.cancel()
            runCatching { controller.unregisterCallback(callback) }
        }
    }
}

private fun MediaController.resolvePlaybackInfo(context: Context): PlaybackInfo {
    val metadata = metadata
    val playbackState = playbackState
    val durationMs =
        metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
    return PlaybackInfo(
        durationMs = durationMs,
        positionMs = playbackState?.computeActualPosition(durationMs) ?: 0L,
        canSeek =
            playbackState?.actions?.and(PlaybackState.ACTION_SEEK_TO) != 0L && durationMs > 0L,
        isPlaying = playbackState?.isActivePlaybackState() == true,
        playOrPause = playbackState?.toPlayPauseAction(context = context, controller = this),
    )
}

private fun PlaybackState.computeActualPosition(durationMs: Long): Long {
    var currentPosition = position.coerceAtLeast(0L)
    if (NotificationMediaManager.isPlayingState(state) && lastPositionUpdateTime > 0L) {
        currentPosition =
            ((playbackSpeed * (SystemClock.elapsedRealtime() - lastPositionUpdateTime)).toLong() +
                    position)
                .coerceAtLeast(0L)
    }
    return if (durationMs > 0L) currentPosition.coerceAtMost(durationMs) else currentPosition
}

private fun PlaybackState.toPlayPauseAction(
    context: Context,
    controller: MediaController,
): MediaAction? {
    val transportControls = controller.transportControls
    return when {
        NotificationMediaManager.isPlayingState(state) && supportsPlaybackAction(PlaybackState.ACTION_PAUSE) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_pause_button),
                action = Runnable { transportControls.pause() },
                contentDescription = context.getString(R.string.controls_media_button_pause),
                background = context.getDrawable(R.drawable.ic_media_pause_button_container),
            )
        supportsPlaybackAction(PlaybackState.ACTION_PLAY) ->
            MediaAction(
                icon = context.getDrawable(R.drawable.ic_media_play_button),
                action = Runnable { transportControls.play() },
                contentDescription = context.getString(R.string.controls_media_button_play),
                background = context.getDrawable(R.drawable.ic_media_play_button_container),
            )
        else -> null
    }
}

private fun PlaybackState.supportsPlaybackAction(@PlaybackState.Actions action: Long): Boolean {
    if (
        (action == PlaybackState.ACTION_PLAY || action == PlaybackState.ACTION_PAUSE) &&
            (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
    ) {
        return true
    }
    return (actions and action) != 0L
}

private fun PlaybackState.isActivePlaybackState(): Boolean {
    return state == PlaybackState.STATE_PLAYING ||
        state == PlaybackState.STATE_BUFFERING ||
        state == PlaybackState.STATE_FAST_FORWARDING ||
        state == PlaybackState.STATE_REWINDING ||
        state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
        state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
}

private data class PlaybackInfo(
    val durationMs: Long,
    val positionMs: Long,
    val canSeek: Boolean,
    val isPlaying: Boolean,
    val playOrPause: MediaAction?,
)

private data class LyricTrack(
    val packageName: String?,
    val mediaId: String?,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

private data class MediaControlState(
    val model: MediaControlChipModel?,
    val token: MediaSession.Token?,
) {
    companion object {
        val Hidden = MediaControlState(model = null, token = null)
    }
}

private fun MediaControlChipModel.withPlaybackInfo(playbackInfo: PlaybackInfo?): MediaControlChipModel {
    if (playbackInfo == null) {
        return this
    }
    return copy(
        playOrPause = playbackInfo.playOrPause ?: playOrPause,
        durationMs = durationMs.takeIf { it > 0L } ?: playbackInfo.durationMs,
        positionMs = playbackInfo.positionMs,
        canBeScrubbed = canBeScrubbed || playbackInfo.canSeek,
        isPlaying = playbackInfo.isPlaying,
    )
}
