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

package com.android.systemui.statusbar.quickactions.island.ui.compose

import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.ui.compose.AccordMediaSurface
import com.android.systemui.axdynamicbar.ui.compose.CallExpanded
import com.android.systemui.axdynamicbar.ui.compose.MediaCard
import com.android.systemui.axdynamicbar.ui.compose.PrimaryCard
import com.android.systemui.axdynamicbar.ui.compose.PromotedOngoingExpanded
import com.android.systemui.axdynamicbar.ui.compose.SportsExpanded
import com.android.systemui.axdynamicbar.ui.compose.TorchExpanded
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.island.alarm.ui.compose.AlarmPopup
import com.android.systemui.statusbar.quickactions.island.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.quickactions.island.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.quickactions.island.stopwatch.ui.compose.StopwatchPopup
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.statusbar.quickactions.island.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupContentModel
import com.android.systemui.statusbar.util.MediaSessionTrackHelper
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Expanded card for the status-bar island. It grows from the pill's top edge, over the status
 * bar, instead of dropping in below it. Content inside the card is inset past the front camera.
 */
@Composable
fun StatusBarPopup(
    viewModel: PopupChipModel.Shown,
    isVisible: Boolean,
    islandActions: IslandActions,
    chipBoundsInScreen: Rect? = null,
) {
    val context = LocalContext.current
    val density = Density(context)
    val anchorView = LocalView.current
    val statusBarHeightPx =
        with(density) { dimensionResource(R.dimen.status_bar_height).roundToPx() }
    val edgePaddingPx = with(density) { PopupEdgePadding.roundToPx() }
    val chipBounds = chipBoundsInScreen
    val popupTopPx = chipBounds?.top?.toInt()
    val chipCenterX = chipBounds?.center?.x
    val cameraBottomPx =
        (anchorView.rootWindowInsets?.displayCutout ?: anchorView.display?.cutout)
            ?.topBoundingRectOrNull()
            ?.bottom
    // Distance from the pill's top to the bottom of the camera hole. The card background fills
    // that region; the controls start below it.
    val cameraClearance =
        if (popupTopPx != null && cameraBottomPx != null) {
            with(density) { (cameraBottomPx - popupTopPx).coerceAtLeast(0).toDp() }
        } else {
            0.dp
        }
    val positionProvider =
        remember(popupTopPx, chipCenterX, statusBarHeightPx, edgePaddingPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val centerX = chipCenterX
                    val x =
                        if (centerX != null) {
                            (centerX - popupContentSize.width / 2f).toInt()
                        } else {
                            anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                        }
                    val y =
                        if (popupTopPx != null) {
                            popupTopPx - edgePaddingPx
                        } else {
                            anchorBounds.top + statusBarHeightPx
                        }
                    return IntOffset(x, y.coerceAtLeast(0))
                }
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        properties =
            PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        onDismissRequest = { viewModel.hidePopup() },
    ) {
        val popupView = LocalView.current
        DisposableEffect(popupView) {
            // The popup window otherwise avoids the status bar and the camera cutout, which
            // pushes the card back down below the pill.
            fun applyWindowLayout() {
                val root = popupView.rootView
                val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
                var changed = false
                if (lp.layoutInDisplayCutoutMode != LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                    lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    changed = true
                }
                if (lp.fitInsetsTypes != 0) {
                    lp.setFitInsetsTypes(0)
                    changed = true
                }
                if (!changed || !root.isAttachedToWindow) return
                root.context
                    .getSystemService(WindowManager::class.java)
                    ?.updateViewLayout(root, lp)
            }
            applyWindowLayout()
            val listener =
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        applyWindowLayout()
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                }
            popupView.addOnAttachStateChangeListener(listener)
            onDispose { popupView.removeOnAttachStateChangeListener(listener) }
        }
        var mediaColor by remember { mutableIntStateOf(0) }
        val mediaSessionManager = islandActions.mediaSessionManager
        DisposableEffect(viewModel.popupContent, mediaSessionManager) {
            if (mediaSessionManager == null) {
                return@DisposableEffect onDispose {}
            }
            val listener =
                object : MediaSessionManager.MediaDataListener {
                    override fun onMediaColorsChanged(color: Int?) {
                        mediaColor = color ?: 0
                    }
                }
            mediaSessionManager.addListener(listener)
            onDispose { mediaSessionManager.removeListener(listener) }
        }

        var popupBoundsInScreen by remember { mutableStateOf<Rect?>(null) }

        val transformOrigin by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f) {
                    TransformOrigin(0.5f, 0f)
                } else {
                    val pivotX =
                        ((chip.center.x - popup.left) / popup.width).coerceIn(0.05f, 0.95f)
                    val pivotY =
                        if (popup.height > 0f) {
                            ((chip.center.y - popup.top) / popup.height).coerceIn(0f, 0.3f)
                        } else {
                            0f
                        }
                    TransformOrigin(pivotX, pivotY)
                }
            }
        }

        val initialScaleFromChip by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f || popup.height <= 0f) {
                    Offset(0.4f, 0.4f)
                } else {
                    Offset(
                        x = (chip.width / popup.width).coerceIn(0.2f, 1f),
                        y = (chip.height / popup.height).coerceIn(0.15f, 1f),
                    )
                }
            }
        }

        val scaleX = remember { Animatable(initialScaleFromChip.x) }
        val scaleY = remember { Animatable(initialScaleFromChip.y) }
        val alpha = remember { Animatable(0f) }

        LaunchedEffect(isVisible, popupBoundsInScreen != null) {
            if (isVisible && popupBoundsInScreen != null) {
                scaleX.snapTo(initialScaleFromChip.x)
                scaleY.snapTo(initialScaleFromChip.y)
                alpha.snapTo(0f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow),
                        )
                    }
                    launch { alpha.animateTo(1f, animationSpec = tween(180)) }
                }
            } else if (!isVisible) {
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = initialScaleFromChip.x,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = initialScaleFromChip.y,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                        )
                    }
                    launch { alpha.animateTo(0f, animationSpec = tween(160)) }
                }
            }
        }

        val mediaModel =
            (viewModel.popupContent as? PopupContentModel.Media)?.model
        val popupActions =
            remember(islandActions, viewModel, mediaModel) {
                object : IslandActions by islandActions {
                    override fun collapseIsland() {
                        viewModel.hidePopup()
                        islandActions.collapseIsland()
                    }

                    override fun dismissEvent(event: IslandEvent) {
                        if (event is IslandEvent.Torch) {
                            turnOffFlashlightIfShown()
                        }
                        islandActions.dismissEvent(event)
                        viewModel.hidePopup()
                    }

                    override fun toggleTorch() {
                        if (!turnOffFlashlightIfShown()) {
                            islandActions.toggleTorch()
                        }
                    }

                    override fun togglePlayPause() {
                        mediaModel?.playOrPause?.action?.run()
                            ?: islandActions.togglePlayPause()
                    }

                    override fun skipNext() {
                        mediaModel?.nextAction?.action?.run()
                            ?: islandActions.skipNext()
                    }

                    override fun skipPrev() {
                        mediaModel?.previousAction?.action?.run()
                            ?: islandActions.skipPrev()
                    }

                    override fun seekTo(position: Long) {
                        mediaModel?.seekTo?.invoke(position)
                            ?: islandActions.seekTo(position)
                    }

                    override fun openMediaApp() {
                        mediaModel?.openApp?.invoke()
                            ?: islandActions.openMediaApp()
                    }

                    override fun sendCustomAction(action: String) {
                        mediaModel?.customActionFor(action)?.action?.run()
                            ?: islandActions.sendCustomAction(action)
                    }

                    private fun turnOffFlashlightIfShown(): Boolean {
                        val content = viewModel.popupContent
                        if (content !is PopupContentModel.Flashlight) return false
                        content.model.turnOff.invoke()
                        return true
                    }
                }
            }

        val hapticsFactory =
            remember(islandActions) {
                islandActions.sliderHapticsViewModelFactory ?: unsupportedHapticsFactory()
            }

        DisposableEffect(popupView) {
            val listener =
                ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (!hasFocus) {
                        viewModel.hidePopup()
                    }
                }
            popupView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { popupView.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(60)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            Box(
                modifier =
                    Modifier.padding(PopupEdgePadding)
                        .wrapContentSize()
                        .onGloballyPositioned { coordinates ->
                            popupBoundsInScreen = coordinates.boundsInScreen(popupView)
                        }
                        .graphicsLayer {
                            this.scaleX = scaleX.value
                            this.scaleY = scaleY.value
                            this.alpha = alpha.value
                            this.transformOrigin = transformOrigin
                        }
            ) {
                when (val popupContent = viewModel.popupContent) {
                    is PopupContentModel.Media -> {
                        val model = popupContent.model
                        val eventMedia =
                            remember(model, mediaColor) {
                                val helper = MediaSessionTrackHelper.getInstance(context)
                                val albumArtDrawable =
                                    (model.artworkIcon as? Icon.Loaded)?.drawable
                                        ?: helper.getMediaBitmap()?.let {
                                            BitmapDrawable(context.resources, it)
                                        }
                                val appIconDrawable = (model.appIcon as? Icon.Loaded)?.drawable
                                IslandEvent.Media(
                                    track = model.songName?.toString().orEmpty(),
                                    artist = model.artistName?.toString().orEmpty(),
                                    isPlaying = model.isPlaying,
                                    albumArt = albumArtDrawable,
                                    progress =
                                        if (model.durationMs > 0L) {
                                            model.positionMs.toFloat() / model.durationMs
                                        } else {
                                            0f
                                        },
                                    duration = model.durationMs,
                                    position = model.positionMs,
                                    packageName = model.packageName.orEmpty(),
                                    appIcon = appIconDrawable,
                                    mediaColor = mediaColor,
                                    customActions = model.toIslandCustomActions(),
                                )
                            }
                        val hasLyrics =
                            model.isDynamicIslandLyricsEnabled &&
                                (!model.lyrics.isNullOrBlank() ||
                                    !model.syncedLyrics.isNullOrBlank())
                        if (hasLyrics) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                                    MediaCard(
                                        event = eventMedia,
                                        interactor = popupActions,
                                        contentTopInset = cameraClearance,
                                    )
                                }
                                AccordMediaSurface(
                                    event = eventMedia,
                                    modifier =
                                        Modifier.widthIn(min = 320.dp, max = 400.dp).height(200.dp),
                                ) {
                                    LyricsCard(model = model)
                                }
                            }
                        } else {
                            Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                                MediaCard(
                                    event = eventMedia,
                                    interactor = popupActions,
                                    contentTopInset = cameraClearance,
                                )
                            }
                        }
                    }
                    is PopupContentModel.ScreenRecord ->
                        ScreenRecordPopup(
                            model = popupContent.model,
                            contentTopInset = cameraClearance,
                        )
                    is PopupContentModel.LiveScore -> {
                        val model = popupContent.model
                        val eventSports =
                            remember(model) {
                                val titleParts =
                                    model.title?.split(
                                        Regex("\\s*vs\\s*|\\s*-\\s*|\\s*@\\s*"),
                                        limit = 2,
                                    ) ?: emptyList()
                                val scoreParts =
                                    model.score.split(Regex("\\s*-\\s*|\\s*:\\s*"), limit = 2)
                                IslandEvent.Sports(
                                    team1Name =
                                        titleParts.getOrNull(0) ?: model.title ?: model.appName,
                                    team2Name = titleParts.getOrNull(1) ?: "",
                                    score1 = scoreParts.getOrNull(0) ?: model.score,
                                    score2 = scoreParts.getOrNull(1) ?: "",
                                    team1Icon = (model.icon as? Icon.Loaded)?.drawable,
                                    statusDetail = model.subtitle.orEmpty(),
                                    league = model.appName,
                                    key = model.key,
                                )
                            }
                        Box(modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)) {
                            PrimaryCard(contentTopInset = cameraClearance) {
                                SportsExpanded(event = eventSports, interactor = popupActions)
                            }
                        }
                    }
                    is PopupContentModel.Flashlight -> {
                        val model = popupContent.model
                        val eventTorch =
                            remember(model) {
                                IslandEvent.Torch(level = model.levelPercent ?: -1, maxLevel = 100)
                            }
                        Box(modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)) {
                            PrimaryCard(contentTopInset = cameraClearance) {
                                TorchExpanded(
                                    event = eventTorch,
                                    interactor = popupActions,
                                    hapticsViewModelFactory = hapticsFactory,
                                )
                            }
                        }
                    }
                    is PopupContentModel.Stopwatch ->
                        StopwatchPopup(
                            model = popupContent.model,
                            contentTopInset = cameraClearance,
                        )
                    is PopupContentModel.Alarm ->
                        AlarmPopup(model = popupContent.model, contentTopInset = cameraClearance)
                    is PopupContentModel.PromotedOngoing -> {
                        Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                            PrimaryCard(contentTopInset = cameraClearance) {
                                PromotedOngoingExpanded(
                                    event = popupContent.event,
                                    interactor = popupActions,
                                )
                            }
                        }
                    }
                    is PopupContentModel.OngoingCall -> {
                        Box(modifier = Modifier.widthIn(min = 320.dp, max = 400.dp)) {
                            PrimaryCard(contentTopInset = cameraClearance) {
                                CallExpanded(event = popupContent.event, interactor = popupActions)
                            }
                        }
                    }
                    PopupContentModel.None -> Unit
                }
            }
        }
    }
}

private fun MediaControlChipModel.toIslandCustomActions(): List<IslandEvent.MediaCustomAction> {
    return listOfNotNull(customAction0?.toIslandCustomAction(), customAction1?.toIslandCustomAction())
}

private fun MediaAction.toIslandCustomAction(): IslandEvent.MediaCustomAction? {
    if (action == null && icon == null) return null
    val label = contentDescription?.toString().orEmpty()
    return IslandEvent.MediaCustomAction(
        label = label,
        action = label,
        icon = icon,
        onClick = action?.let { runnable -> { runnable.run() } },
    )
}

private fun MediaControlChipModel.customActionFor(action: String): MediaAction? {
    return listOfNotNull(customAction0, customAction1).firstOrNull {
        it.contentDescription?.toString() == action
    }
}

internal fun LayoutCoordinates.boundsInScreen(view: View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

private val PopupEdgePadding = 8.dp

private fun unsupportedHapticsFactory(): SliderHapticsViewModel.Factory {
    return object : SliderHapticsViewModel.Factory {
        override fun create(
            interactionSource: androidx.compose.foundation.interaction.InteractionSource,
            sliderRange: ClosedFloatingPointRange<Float>,
            orientation: androidx.compose.foundation.gestures.Orientation,
            sliderHapticFeedbackConfig: com.android.systemui.haptics.slider.SliderHapticFeedbackConfig,
            sliderTrackerConfig: com.android.systemui.haptics.slider.SeekableSliderTrackerConfig,
        ): SliderHapticsViewModel {
            throw UnsupportedOperationException()
        }
    }
}
