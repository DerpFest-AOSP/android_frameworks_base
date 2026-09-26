/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.graphics.Rect
import android.graphics.RectF
import android.view.DisplayCutout
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.observeDynamicIslandScale
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.observeDynamicIslandWidth
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.island.screenrecord.shared.model.ScreenRecordPopupModel
import kotlin.math.ceil
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** Single centered status bar capsule styled like a compact dynamic island. */
@Composable
fun StatusBarDynamicIslandChip(
    viewModel: PopupChipModel.Shown,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMediaChip = viewModel.popupContent is PopupContentModel.Media
    val chipShape = RoundedCornerShape(50)
    val colors = viewModel.colors
    val heightScale = rememberDynamicIslandHeightScale()
    val view = LocalView.current
    val hapticOnTap: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onTap()
    }
    val mediaOpenApp: (() -> Unit)? =
        (viewModel.popupContent as? PopupContentModel.Media)
            ?.takeIf { it.model.isPlaying }
            ?.model
            ?.openApp
    val hapticOnLongPress: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        mediaOpenApp?.invoke()
    }
    val chipBackgroundColor =
        colors.chipBackground(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipContentColor =
        colors.chipContent(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipOutline =
        colors.chipOutline(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val configuredWidth = rememberDynamicIslandWidth()
    if (viewModel.popupContent.isUtilityStatusContent() && viewModel.icons.isNotEmpty()) {
        UtilityStatusIslandChip(
            viewModel = viewModel,
            onTap = hapticOnTap,
            cutoutSpec = cutoutSpec,
            heightScale = heightScale,
            configuredWidth = configuredWidth,
            chipBackgroundColor = chipBackgroundColor,
            chipContentColor = chipContentColor,
            chipOutline = chipOutline,
            modifier = modifier,
        )
        return
    }
    val hasInlineTimer = viewModel.popupContent is PopupContentModel.Stopwatch
    val trailingDecorationWidth =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    14.dp
                } else if (pageCount > 1) {
                    11.dp
                } else {
                    0.dp
                }
            is PopupContentModel.ScreenRecord -> 11.dp
            else -> {
                if (pageCount > 1) 11.dp else 0.dp
            }
        }
    val leadingDecorationWidth =
        when {
            viewModel.icons.isEmpty() -> 0.dp
            else -> 18.dp + (8.dp * (viewModel.icons.size - 1))
        }
    val maxTextWidth =
        (configuredWidth - 24.dp - leadingDecorationWidth - trailingDecorationWidth)
            .coerceAtLeast(0.dp)
    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown)

    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = 32.dp * heightScale)
                .width(configuredWidth)
                .graphicsLayer { scaleX = collapseState.scale }
                .clip(chipShape)
                .background(chipBackgroundColor)
                .border(width = 1.dp, color = chipOutline, shape = chipShape)
                .combinedClickable(
                    onClick = hapticOnTap,
                    onLongClick = mediaOpenApp?.let { { hapticOnLongPress() } },
                )
                .padding(horizontal = 12.dp, vertical = 7.dp * heightScale)
                .graphicsLayer { alpha = collapseState.contentAlpha },
        horizontalArrangement =
            if (isMediaChip) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.icons.forEachIndexed { index, chipIcon ->
            val isArtworkLike =
                index == 0 &&
                    (viewModel.popupContent is PopupContentModel.Media ||
                        viewModel.popupContent is PopupContentModel.LiveScore)
            Icon(
                icon = chipIcon.icon,
                modifier =
                    Modifier.size(if (isArtworkLike) 18.dp else 16.dp)
                        .then(
                            if (isArtworkLike) {
                                Modifier.clip(RoundedCornerShape(5.dp))
                            } else {
                                Modifier
                            }
                        ),
                tint = if (isArtworkLike) Color.Unspecified else chipContentColor,
            )
        }

        viewModel.chipText
            ?.takeIf {
                !isMediaChip &&
                    viewModel.popupContent !is PopupContentModel.ScreenRecord &&
                    !hasInlineTimer &&
                    it.isNotBlank()
            }
            ?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = chipContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxTextWidth),
                )
            }

        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    AudioReactiveBars(
                        isPlaying = true,
                        color = chipContentColor,
                    )
                } else if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                    is ScreenRecordPopupModel.Recording ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                }
            is PopupContentModel.Stopwatch ->
                StatusContent(
                    text =
                        popupContent.model.elapsedTimeText
                            ?: rememberElapsedDurationText(
                                popupContent.model.baseElapsedRealtimeMs
                            ),
                    color = chipContentColor,
                    showSwipeHint = pageCount > 1,
                )
            else -> {
                if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            }
        }
    }
}

@Composable
private fun UtilityStatusIslandChip(
    viewModel: PopupChipModel.Shown,
    onTap: () -> Unit,
    cutoutSpec: DynamicIslandCutoutSpec,
    heightScale: Float = 1f,
    configuredWidth: Dp,
    chipBackgroundColor: Color,
    chipContentColor: Color,
    chipOutline: Color,
    modifier: Modifier = Modifier,
) {
    // The cutout gap is physical, so the pill cannot shrink through the camera hole.
    val contentFloor =
        UtilitySideChromeWidth + cutoutSpec.embeddedGapWidth + UtilityTextMinWidth
    val connectedIslandWidth = maxOf(contentFloor, configuredWidth)
    val utilityText =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting -> "${model.secondsUntilStarted}s"
                    is ScreenRecordPopupModel.Recording ->
                        rememberElapsedDurationText(model.startElapsedRealtimeMs)
                }
            is PopupContentModel.Stopwatch ->
                popupContent.model.elapsedTimeText
                    ?: rememberElapsedDurationText(popupContent.model.baseElapsedRealtimeMs)
            is PopupContentModel.Alarm -> viewModel.chipText.orEmpty()
            is PopupContentModel.Flashlight -> viewModel.chipText.orEmpty()
            else -> ""
        }
    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown)

    Row(
        modifier =
            modifier
                .graphicsLayer { scaleX = collapseState.scale }
                .defaultMinSize(minHeight = 32.dp * heightScale)
                .width(connectedIslandWidth)
                .clip(RoundedCornerShape(50))
                .background(chipBackgroundColor)
                .border(width = 1.dp, color = chipOutline, shape = RoundedCornerShape(50))
                .clickable(onClick = onTap)
                .graphicsLayer { alpha = collapseState.contentAlpha },
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(10.dp))
        Icon(
            icon = viewModel.icons.first().icon,
            modifier = Modifier.size(16.dp),
            tint = chipContentColor,
        )
        Spacer(modifier = Modifier.width(cutoutSpec.embeddedGapWidth))
        Box(
            modifier =
                Modifier.weight(1f)
                    .padding(
                        start = 6.dp,
                        top = 7.dp * heightScale,
                        bottom = 7.dp * heightScale,
                        end = 6.dp,
                    ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = utilityText,
                style = MaterialTheme.typography.labelLarge,
                color = chipContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
    }
}

@Composable
private fun StatusContent(
    text: String,
    color: Color,
    showSwipeHint: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
        )
        if (showSwipeHint) {
            SwipeHint(color = color.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun SwipeHint(color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier = Modifier.size(width = 3.dp, height = 3.dp)
                    .background(color = color, shape = CircleShape)
            )
        }
    }
}

private val MinDynamicIslandWidth = 80.dp
private val MaxDynamicIslandWidth = 200.dp
private val UtilitySideChromeWidth = 36.dp
private val UtilityTextMinWidth = 48.dp
private val DynamicIslandEmbeddedGapFallbackWidth = 38.dp
private val DynamicIslandEmbeddedGapMinWidth = 34.dp
private val DynamicIslandEmbeddedGapMaxWidth = 88.dp
private val DynamicIslandEmbeddedGapSidePadding = 10.dp

data class DynamicIslandCutoutSpec(
    val embeddedGapWidth: Dp,
    val horizontalOffset: Dp,
    /** The camera's centre in window pixels, or null with no top cutout. */
    val cutoutCenterX: Float? = null,
)

@Composable
fun rememberDynamicIslandCutoutSpec(): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    val view = LocalView.current
    val displayCutout = view.rootWindowInsets?.displayCutout ?: view.display?.cutout
    val topCutout = displayCutout?.topBoundingRectOrNull()
    val rootWidthPx =
        when {
            view.rootView.width > 0 -> view.rootView.width
            view.width > 0 -> view.width
            else -> view.resources.configuration.windowConfiguration.maxBounds.width()
        }

    return with(density) {
        if (topCutout == null || rootWidthPx <= 0) {
            DynamicIslandCutoutSpec(
                embeddedGapWidth = DynamicIslandEmbeddedGapFallbackWidth,
                horizontalOffset = 0.dp,
            )
        } else {
            val embeddedGapWidthDp =
                (topCutout.width().toDp() + (DynamicIslandEmbeddedGapSidePadding * 2))
                    .coerceIn(
                        DynamicIslandEmbeddedGapMinWidth,
                        DynamicIslandEmbeddedGapMaxWidth,
                    )
            val horizontalOffsetDp = (topCutout.exactCenterX() - (rootWidthPx / 2f)).toDp()
            DynamicIslandCutoutSpec(
                embeddedGapWidth = embeddedGapWidthDp,
                horizontalOffset = horizontalOffsetDp,
                cutoutCenterX = topCutout.exactCenterX(),
            )
        }
    }
}

/**
 * The top cutout's extent, from its path when there is one. Some devices' bounding rects do not
 * match their cutout (phone2 reports 454-540 for a camera drawn at 511-569), and the island has
 * to sit on the camera itself.
 */
private fun DisplayCutout.topBoundingRectOrNull(): Rect? {
    val top = getBoundingRectTop().takeUnless { it.isEmpty } ?: return null
    val path = cutoutPath ?: return top
    val bounds = RectF()
    path.computeBounds(bounds, true)
    if (bounds.isEmpty || bounds.top > top.bottom) return top
    return Rect(
        bounds.left.toInt(),
        bounds.top.toInt(),
        ceil(bounds.right).toInt(),
        ceil(bounds.bottom).toInt(),
    )
}

private fun PopupContentModel.isUtilityStatusContent(): Boolean {
    return this is PopupContentModel.ScreenRecord ||
        this is PopupContentModel.Stopwatch ||
        this is PopupContentModel.Alarm ||
        this is PopupContentModel.Flashlight
}

@Composable
private fun rememberDynamicIslandWidth(): Dp {
    val context = LocalContext.current
    val widthDp by
        remember { observeDynamicIslandWidth(context) }.collectAsState(initial = 110)
    return widthDp.dp.coerceIn(MinDynamicIslandWidth, MaxDynamicIslandWidth)
}

@Composable
private fun rememberDynamicIslandHeightScale(): Float {
    val context = LocalContext.current
    val heightScale by
        remember { observeDynamicIslandScale(context, DynamicIslandFeatureSettings.HEIGHT_SCALE) }
            .collectAsState(initial = 1f)
    return heightScale
}

private data class DynamicIslandCollapseState(val scale: Float, val contentAlpha: Float)

@Composable
private fun rememberDynamicIslandCollapseState(isOpen: Boolean): DynamicIslandCollapseState {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.0005f) }
    val currentIsOpen by rememberUpdatedState(isOpen)

    LaunchedEffect(Unit) {
        snapshotFlow { currentIsOpen }
            .drop(1)
            .collectLatest { open ->
                if (open) {
                    scaleX.animateTo(
                        targetValue = 0f,
                        animationSpec =
                            spring(
                                dampingRatio = 0.9f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                    )
                } else {
                    scaleX.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            keyframes {
                                durationMillis = 380
                                0f at 0
                                1.08f at 240 using FastOutSlowInEasing
                                0.96f at 320
                                1f at 380
                            },
                    )
                }
            }
    }
    val fadeThreshold = 0.7f
    val alpha = (scaleX.value / fadeThreshold).coerceIn(0f, 1f)
    return DynamicIslandCollapseState(scale = scaleX.value, contentAlpha = alpha)
}
