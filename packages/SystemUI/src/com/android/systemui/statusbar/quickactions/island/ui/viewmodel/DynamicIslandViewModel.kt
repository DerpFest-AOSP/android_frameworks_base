/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.ui.viewmodel

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.chips.call.ui.viewmodel.CallChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.IslandChipClaims
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.quickactions.island.alarm.ui.viewmodel.AlarmPopupChipViewModel
import com.android.systemui.statusbar.quickactions.island.flashlight.ui.viewmodel.FlashlightPopupChipViewModel
import com.android.systemui.statusbar.quickactions.island.liveactivity.ui.viewmodel.LiveActivityPopupChipsViewModel
import com.android.systemui.statusbar.quickactions.island.livescore.ui.viewmodel.LiveScorePopupChipViewModel
import com.android.systemui.statusbar.quickactions.island.media.ui.viewmodel.MediaControlChipViewModel
import com.android.systemui.statusbar.quickactions.island.ongoingcall.ui.viewmodel.OngoingCallsPopupChipViewModel
import com.android.systemui.statusbar.quickactions.island.promotedongoing.ui.viewmodel.PromotedOngoingPopupChipViewModel
import com.android.systemui.statusbar.quickactions.island.screenrecord.ui.viewmodel.ScreenRecordPopupChipViewModel
import com.android.systemui.statusbar.quickactions.island.stopwatch.ui.viewmodel.StopwatchPopupChipViewModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipId
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupContentModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Collects phone dynamic-island pages and exposes the currently visible [PopupChipModel.Shown]
 * chips. Scene container hosts this as a centered overlay on the home status bar.
 */
class DynamicIslandViewModel
internal constructor(
    private val context: Context,
    private val mediaControlChip: IslandChipViewModel,
    private val screenRecordChip: IslandChipViewModel,
    private val liveScoreChip: IslandChipViewModel,
    private val flashlightChip: IslandChipViewModel,
    private val stopwatchChip: IslandChipViewModel,
    private val alarmChip: IslandChipViewModel,
    private val ongoingCallChip: IslandChipViewModel,
    private val promotedOngoingChip: IslandChipViewModel,
    private val keyguardStateController: KeyguardStateController? = null,
    private val shadeInteractor: ShadeInteractor? = null,
    private val liveActivityChips: LiveActivityPopupChipsViewModel? = null,
) : ExclusiveActivatable() {

    @AssistedInject
    constructor(
        @Application context: Context,
        mediaControlChipFactory: MediaControlChipViewModel.Factory,
        screenRecordChipFactory: ScreenRecordPopupChipViewModel.Factory,
        liveScoreChipFactory: LiveScorePopupChipViewModel.Factory,
        flashlightChipFactory: FlashlightPopupChipViewModel.Factory,
        stopwatchChipFactory: StopwatchPopupChipViewModel.Factory,
        alarmChipFactory: AlarmPopupChipViewModel.Factory,
        ongoingCallChipFactory: OngoingCallsPopupChipViewModel.Factory,
        promotedOngoingChipFactory: PromotedOngoingPopupChipViewModel.Factory,
        keyguardStateController: KeyguardStateController,
        shadeInteractor: ShadeInteractor,
        liveActivityChipsFactory: LiveActivityPopupChipsViewModel.Factory,
    ) : this(
        context = context,
        mediaControlChip = mediaControlChipFactory.create(),
        screenRecordChip = screenRecordChipFactory.create(),
        liveScoreChip = liveScoreChipFactory.create(),
        flashlightChip = flashlightChipFactory.create(),
        stopwatchChip = stopwatchChipFactory.create(),
        alarmChip = alarmChipFactory.create(),
        ongoingCallChip = ongoingCallChipFactory.create(),
        promotedOngoingChip = promotedOngoingChipFactory.create(),
        keyguardStateController = keyguardStateController,
        shadeInteractor = shadeInteractor,
        liveActivityChips = liveActivityChipsFactory.create(),
    )

    private var isDynamicIslandEnabled by mutableStateOf(readDynamicIslandEnabled())
    private val dynamicIslandObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isDynamicIslandEnabled = readDynamicIslandEnabled()
                if (!isDynamicIslandEnabled) {
                    currentShownPopupChipId = null
                }
            }
        }

    /** The ID of the current chip that is showing its popup, or `null` if no chip is shown. */
    private var currentShownPopupChipId by mutableStateOf<PopupChipId?>(null)

    /** Chips swiped away, with what they showed then; they return once that changes. */
    private val hiddenChips = mutableStateMapOf<PopupChipId, Any?>()

    /** Package of the top activity, so a media chip is not shown over the app it stands for. */
    private var foregroundPackage by mutableStateOf<String?>(null)

    /** True while the lockscreen is showing, so a ringing call does not open the popup there. */
    private var onLockscreen by mutableStateOf(keyguardStateController?.isShowing == true)

    /** True while the shade covers the status bar. */
    private var shadeExpanded by mutableStateOf(false)

    private val keyguardCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {
                onLockscreen = keyguardStateController?.isShowing == true
            }
        }

    private val taskStackListener =
        object : TaskStackChangeListener {
            override fun onTaskStackChanged() {
                updateForegroundPackage()
            }
        }

    private fun updateForegroundPackage() {
        val top =
            runCatching {
                    ActivityManagerWrapper.getInstance().runningTask?.topActivity?.packageName
                }
                .getOrNull()
        if (top == foregroundPackage) return
        foregroundPackage = top
        // Opening the player a media chip belongs to puts that popup away. Other chips stay.
        if (
            top != null &&
                currentShownPopupChipId == PopupChipId.MediaControl &&
                incomingPopupChipBundle.media.mediaPackageName() == top
        ) {
            currentShownPopupChipId = null
        }
    }

    private val incomingPopupChipBundle: PopupChipBundle by derivedStateOf {
        PopupChipBundle(
            media = mediaControlChip.chip,
            screenRecord = screenRecordChip.chip,
            liveScore = liveScoreChip.chip,
            flashlight = flashlightChip.chip,
            stopwatch = stopwatchChip.chip,
            alarm = alarmChip.chip,
            ongoingCall = ongoingCallChip.chip,
            promotedOngoing = promotedOngoingChip.chip,
        )
    }

    val shownPopupChips: List<PopupChipModel.Shown> by derivedStateOf {
        if (!isDynamicIslandEnabled) {
            return@derivedStateOf emptyList()
        }

        val bundle = incomingPopupChipBundle
        listOfNotNull(
                bundle.media,
                bundle.screenRecord,
                bundle.liveScore,
                bundle.stopwatch,
                bundle.alarm,
                bundle.flashlight,
                bundle.ongoingCall,
            )
            .filterIsInstance<PopupChipModel.Shown>()
            .plus(liveActivityChips?.chips.orEmpty())
            .filterNot { it.isOwnedByForegroundApp() }
            .filterNot { chip ->
                chip.chipId in hiddenChips && hiddenChips[chip.chipId] == chip.hideKey()
            }
            .map { chip ->
                chip.copy(
                    isPopupShown = chip.chipId == currentShownPopupChipId,
                    showPopup = { currentShownPopupChipId = chip.chipId },
                    hidePopup = {
                        if (currentShownPopupChipId == chip.chipId) {
                            currentShownPopupChipId = null
                        }
                    },
                    hide = {
                        if (chip.chipId.isHideable()) {
                            hiddenChips[chip.chipId] = chip.hideKey()
                            if (currentShownPopupChipId == chip.chipId) {
                                currentShownPopupChipId = null
                            }
                        }
                    },
                )
            }
    }

    override suspend fun onActivated() {
        coroutineScope {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND),
                false,
                dynamicIslandObserver,
                UserHandle.USER_ALL,
            )
            dynamicIslandObserver.onChange(false)

            TaskStackChangeListeners.getInstance().registerTaskStackListener(taskStackListener)
            updateForegroundPackage()
            keyguardStateController?.addCallback(keyguardCallback)
            onLockscreen = keyguardStateController?.isShowing == true

            launch { shadeInteractor?.isAnyExpanded?.collect { shadeExpanded = it } }
            launch { showRingingCall() }
            launch { snapshotFlow { islandClaims }.collect { IslandChipClaims.update(it) } }
            launch {
                // A swiped-away chip comes back once it ends or shows something new.
                snapshotFlow {
                        listOf(
                                mediaControlChip.chip,
                                screenRecordChip.chip,
                                liveScoreChip.chip,
                                flashlightChip.chip,
                                stopwatchChip.chip,
                                alarmChip.chip,
                                ongoingCallChip.chip,
                                promotedOngoingChip.chip,
                            )
                            .filterIsInstance<PopupChipModel.Shown>()
                            .plus(liveActivityChips?.chips.orEmpty())
                            .associate { it.chipId to it.hideKey() }
                    }
                    .collect { present ->
                        hiddenChips.keys.toList().forEach { id ->
                            if (id !in present || present[id] != hiddenChips[id]) {
                                hiddenChips.remove(id)
                            }
                        }
                    }
            }
            launch { mediaControlChip.activate() }
            launch { screenRecordChip.activate() }
            launch { liveScoreChip.activate() }
            launch { flashlightChip.activate() }
            launch { stopwatchChip.activate() }
            launch { alarmChip.activate() }
            launch { ongoingCallChip.activate() }
            launch { promotedOngoingChip.activate() }
            liveActivityChips?.let { launch { it.activate() } }
            try {
                awaitCancellation()
            } finally {
                context.contentResolver.unregisterContentObserver(dynamicIslandObserver)
                TaskStackChangeListeners.getInstance().unregisterTaskStackListener(taskStackListener)
                keyguardStateController?.removeCallback(keyguardCallback)
                IslandChipClaims.update(IslandChipClaims.Claims())
            }
        }
    }

    private data class PopupChipBundle(
        val media: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.MediaControl),
        val screenRecord: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.ScreenRecord),
        val liveScore: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.LiveScore),
        val flashlight: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.Flashlight),
        val stopwatch: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.Stopwatch),
        val alarm: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.Alarm),
        val ongoingCall: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.OngoingCall),
        val promotedOngoing: PopupChipModel =
            PopupChipModel.Hidden(chipId = PopupChipId.PromotedOngoing),
    )

    /**
     * Whether this chip belongs to the app now in the foreground. The media chip steps aside while
     * its own player is open.
     */
    /** What a hidden chip has to change to come back: another song, or another stage of a call. */
    private fun PopupChipModel.Shown.hideKey(): Any? =
        when (val content = popupContent) {
            is PopupContentModel.Media ->
                listOf(
                    content.model.packageName,
                    content.model.songName?.toString(),
                    content.model.artistName?.toString(),
                )
            is PopupContentModel.OngoingCall -> !content.event.connected
            is PopupContentModel.LiveActivity ->
                listOf(content.model.title, content.model.text, content.model.shortText, content.model.progress)
            else -> null
        }

    /** What the island shows itself, so the stock status bar chips leave those out. */
    private val islandClaims: IslandChipClaims.Claims by derivedStateOf {
        if (!isDynamicIslandEnabled) return@derivedStateOf IslandChipClaims.Claims()
        IslandChipClaims.Claims(
            notificationKeys =
                shownPopupChips.mapNotNullTo(mutableSetOf()) { chip ->
                    when (val content = chip.popupContent) {
                        is PopupContentModel.OngoingCall ->
                            CallChipViewModel.KEY_PREFIX + content.event.sbn.key
                        is PopupContentModel.PromotedOngoing -> content.event.sbn.key
                        is PopupContentModel.LiveActivity -> content.model.key
                        else -> null
                    }
                },
            screenRecord = shownPopupChips.any { it.chipId == PopupChipId.ScreenRecord },
        )
    }

    /** Privacy indicators say that a camera or the screen is in use and are never swiped away. */
    private fun PopupChipId.isHideable(): Boolean =
        this != PopupChipId.AvControlsIndicator &&
            this != PopupChipId.ShareScreenPrivacyIndicator

    private fun PopupChipModel.Shown.isOwnedByForegroundApp(): Boolean {
        val open = foregroundPackage ?: return false
        return when (val content = popupContent) {
            is PopupContentModel.Media -> content.model.packageName == open
            is PopupContentModel.OngoingCall -> content.event.sbn.packageName == open
            is PopupContentModel.LiveActivity -> content.model.packageName == open
            else -> false
        }
    }

    /**
     * Opens the call popup while a call is ringing and puts it away once the call is picked up,
     * ends, or moves on. Skipped while the island is hidden on the lockscreen or under the shade.
     * The chip steps aside on the dialer's own screen through [isOwnedByForegroundApp].
     */
    private suspend fun showRingingCall() {
        var held: PopupChipId? = null
        snapshotFlow {
                val call = ongoingCallChip.chip as? PopupChipModel.Shown
                val event = (call?.popupContent as? PopupContentModel.OngoingCall)?.event
                val ringing =
                    event != null &&
                        !event.connected &&
                        event.callType == "Phone:incoming"
                Triple(if (ringing) call.chipId else null, onLockscreen, shadeExpanded)
            }
            .distinctUntilChanged()
            .collect { (ringingId, locked, shade) ->
                held?.let { if (currentShownPopupChipId == it) currentShownPopupChipId = null }
                held = null
                if (ringingId != null && isDynamicIslandEnabled && !locked && !shade) {
                    currentShownPopupChipId = ringingId
                    held = ringingId
                }
            }
    }

    private fun PopupChipModel.mediaPackageName(): String? {
        val shown = this as? PopupChipModel.Shown ?: return null
        return (shown.popupContent as? PopupContentModel.Media)?.model?.packageName
    }

    private fun readDynamicIslandEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    @AssistedFactory
    interface Factory {
        fun create(): DynamicIslandViewModel
    }
}
