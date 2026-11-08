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
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.observeDynamicIslandScale
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.observeDynamicIslandVerticalOffset
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.observeDynamicIslandWidth
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.island.screenrecord.shared.model.ScreenRecordPopupModel
import kotlin.math.ceil
import kotlinx.coroutines.delay
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
    pageIndex: Int = 0,
    onNextPage: () -> Unit = {},
) {
    val isMediaChip = viewModel.popupContent is PopupContentModel.Media
    val isCallChip = viewModel.popupContent is PopupContentModel.OngoingCall
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
            pageCount = pageCount,
            pageIndex = pageIndex,
            onNextPage = onNextPage,
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
                } else {
                    pageDotsWidth(pageCount)
                }
            is PopupContentModel.ScreenRecord -> pageDotsWidth(pageCount).coerceAtLeast(11.dp)
            else -> pageDotsWidth(pageCount)
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
    // A chip centred on the camera keeps its icon and text on either side of the lens, so short
    // labels are not hidden behind it.
    val cameraGap =
        cutoutSpec.embeddedGapWidth.takeIf { !isMediaChip && cutoutSpec.cutoutCenterX != null }

    CameraSplitRow(
        leadingCount = viewModel.icons.size,
        cameraGap = cameraGap,
        modifier =
            modifier
                .defaultMinSize(
                    minHeight = (32.dp + if (isCallChip) CallExtraHeight else 0.dp) * heightScale
                )
                .width(if (isCallChip) maxOf(configuredWidth, CallMinWidth) else configuredWidth)
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
            UprightContent {
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
        }

        viewModel.chipText
            ?.takeIf {
                !isMediaChip &&
                    viewModel.popupContent !is PopupContentModel.ScreenRecord &&
                    !hasInlineTimer &&
                    it.isNotBlank()
            }
            ?.let { text ->
                UprightContent {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        color = chipContentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = maxTextWidth),
                    )
                }
            }

        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    UprightContent {
                        AudioReactiveBars(
                            isPlaying = true,
                            color = chipContentColor,
                        )
                    }
                } else if (pageCount > 1) {
                    UprightContent {
                        PageDots(
                            count = pageCount,
                            index = pageIndex,
                            color = chipContentColor,
                            onClick = onNextPage,
                        )
                    }
                }
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            pageCount = pageCount,
                            pageIndex = pageIndex,
                            onNextPage = onNextPage,
                        )
                    is ScreenRecordPopupModel.Recording ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            pageCount = pageCount,
                            pageIndex = pageIndex,
                            onNextPage = onNextPage,
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
                    pageCount = pageCount,
                    pageIndex = pageIndex,
                    onNextPage = onNextPage,
                )
            else -> {
                if (pageCount > 1) {
                    UprightContent {
                        PageDots(
                            count = pageCount,
                            index = pageIndex,
                            color = chipContentColor,
                            onClick = onNextPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UtilityStatusIslandChip(
    viewModel: PopupChipModel.Shown,
    onTap: () -> Unit,
    pageCount: Int,
    pageIndex: Int,
    onNextPage: () -> Unit,
    cutoutSpec: DynamicIslandCutoutSpec,
    heightScale: Float = 1f,
    configuredWidth: Dp,
    chipBackgroundColor: Color,
    chipContentColor: Color,
    chipOutline: Color,
    modifier: Modifier = Modifier,
) {
    // The cutout gap is physical, so the pill cannot shrink through the camera hole.
    val contentTint = viewModel.contentTint ?: chipContentColor
    val startText =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Alarm ->
                rememberAlarmCountdownText(popupContent.model.triggerTimeMs)
            else -> viewModel.startText
        }
    val dotsWidth = pageDotsWidth(pageCount)
    val startWidth = if (startText != null) CompactUtilityStartTextWidth else 0.dp
    val contentFloor =
        UtilitySideChromeWidth +
            cutoutSpec.embeddedGapWidth +
            UtilityTextMinWidth +
            dotsWidth +
            startWidth
    val connectedIslandWidth = maxOf(contentFloor, configuredWidth)
    // Centred on a camera, the two sides have to be as wide as each other, or the gap drifts off
    // the lens towards the shorter side and the text ends up behind it.
    val splitAroundCamera = cutoutSpec.cutoutCenterX != null
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

    val icon: @Composable () -> Unit = {
        UprightContent {
            Icon(
                icon = viewModel.icons.first().icon,
                modifier = Modifier.size(16.dp),
                tint = contentTint,
            )
        }
    }
    val startLabel: @Composable () -> Unit = {
        if (startText != null) {
            UprightContent {
                Text(
                    text = startText,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val pageDots: @Composable () -> Unit = {
        if (pageCount > 1) {
            UprightContent {
                PageDots(
                    count = pageCount,
                    index = pageIndex,
                    color = chipContentColor,
                    onClick = onNextPage,
                )
            }
        }
    }

    val chipModifier =
        modifier
            .graphicsLayer { scaleX = collapseState.scale }
            .defaultMinSize(minHeight = 32.dp * heightScale)
            .then(
                if (splitAroundCamera) {
                    Modifier.widthIn(
                        min = contentFloor,
                        max = maxOf(contentFloor, CompactUtilityCameraSplitMaxWidth),
                    )
                } else {
                    Modifier.width(connectedIslandWidth)
                }
            )
            .clip(RoundedCornerShape(50))
            .background(chipBackgroundColor)
            .border(width = 1.dp, color = chipOutline, shape = RoundedCornerShape(50))
            .clickable(onClick = onTap)
            .graphicsLayer { alpha = collapseState.contentAlpha }
            .then(if (splitAroundCamera) Modifier.padding(horizontal = 10.dp) else Modifier)
    if (splitAroundCamera) {
        CameraSplitRow(
            leadingCount = if (startText != null) 2 else 1,
            cameraGap = cutoutSpec.embeddedGapWidth,
            modifier = chipModifier,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            startLabel()
            UprightContent {
                Text(
                    text = utilityText,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            pageDots()
        }
    } else {
        // CameraSplitRow's content is not a Row, so the leftover width has to be taken here.
        Row(
            modifier = chipModifier,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(10.dp))
            icon()
            if (startText != null) {
                Spacer(modifier = Modifier.width(6.dp))
                startLabel()
            }
            Spacer(modifier = Modifier.width(cutoutSpec.embeddedGapWidth))
            UprightContent(modifier = Modifier.weight(1f)) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = 6.dp,
                                top = 7.dp * heightScale,
                                bottom = 7.dp * heightScale,
                                end = 6.dp,
                            ),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = utilityText,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    pageDots()
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
        }
    }
}

/**
 * The time left until the alarm as short text such as `in 7h 5m`, kept current once a minute. Null
 * when there is no upcoming alarm to count down to, e.g. while one is ringing.
 */
@Composable
private fun rememberAlarmCountdownText(triggerTimeMs: Long): String? {
    val context = LocalContext.current
    val now by
        produceState(System.currentTimeMillis(), triggerTimeMs) {
            while (triggerTimeMs > 0L) {
                delay(AlarmCountdownTickMs)
                value = System.currentTimeMillis()
            }
        }
    if (triggerTimeMs <= 0L) return null
    val minutes = ((triggerTimeMs - now + 59_999L) / 60_000L).toInt()
    if (minutes <= 0) return null
    val hours = minutes / 60
    return when {
        minutes < 60 -> context.getString(R.string.dynamic_island_alarm_in_minutes, minutes)
        minutes < 24 * 60 && minutes % 60 == 0 ->
            context.getString(R.string.dynamic_island_alarm_in_hours, hours)
        minutes < 24 * 60 ->
            context.getString(R.string.dynamic_island_alarm_in_hours_minutes, hours, minutes % 60)
        else -> context.getString(R.string.dynamic_island_alarm_in_days, minutes / (24 * 60))
    }
}

@Composable
private fun StatusContent(
    text: String,
    color: Color,
    pageCount: Int,
    pageIndex: Int,
    onNextPage: () -> Unit,
) {
    UprightContent {
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
            if (pageCount > 1) {
                PageDots(count = pageCount, index = pageIndex, color = color, onClick = onNextPage)
            }
        }
    }
}

/** Width of [PageDots] for [count] pages, including the padding that makes it tappable. */
private fun pageDotsWidth(count: Int): Dp {
    if (count <= 1) return 0.dp
    val visible = minOf(count, MaxPageDots)
    return PageDotActiveWidth + (PageDotSize + PageDotSpacing) * (visible - 1) + PageDotsTapPadding * 2
}

/**
 * One dot per stacked chip with the current one stretched, so it shows how many chips are stacked
 * and where this one is among them. A tap goes to the next chip. With more than [MaxPageDots]
 * chips, the dots follow the current one.
 */
@Composable
private fun PageDots(count: Int, index: Int, color: Color, onClick: () -> Unit) {
    val visible = minOf(count, MaxPageDots)
    val first = (index - visible / 2).coerceIn(0, count - visible)
    Row(
        modifier =
            Modifier.clickable(onClick = onClick)
                .padding(horizontal = PageDotsTapPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(PageDotSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(visible) { slot ->
            val isCurrent = first + slot == index
            val width by
                animateDpAsState(
                    targetValue = if (isCurrent) PageDotActiveWidth else PageDotSize,
                    label = "pageDotWidth",
                )
            Box(
                modifier =
                    Modifier.size(width = width, height = PageDotSize)
                        .background(
                            color = color.copy(alpha = if (isCurrent) 0.9f else 0.45f),
                            shape = CircleShape,
                        )
            )
        }
    }
}

/**
 * A [Row] that, given a [cameraGap], keeps the first [leadingCount] children before a centred gap
 * and the rest after it. Both sides take the same width so the gap stays over the camera.
 */
@Composable
private fun CameraSplitRow(
    leadingCount: Int,
    cameraGap: Dp?,
    modifier: Modifier,
    horizontalArrangement: Arrangement.Horizontal,
    verticalAlignment: Alignment.Vertical,
    content: @Composable () -> Unit,
) {
    if (cameraGap == null) {
        Row(
            modifier = modifier,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
        ) {
            content()
        }
        return
    }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = cameraGap.roundToPx()
        val spacingPx = CameraSplitSpacing.roundToPx()
        val sideMaxPx = ((constraints.maxWidth - gapPx) / 2).coerceAtLeast(0)

        fun measureSide(items: List<Measurable>): List<Placeable> {
            var remaining = sideMaxPx
            return items.map { item ->
                item
                    .measure(
                        Constraints(
                            maxWidth = remaining.coerceAtLeast(0),
                            maxHeight = constraints.maxHeight,
                        )
                    )
                    .also { remaining -= it.width + spacingPx }
            }
        }

        fun List<Placeable>.span() =
            if (isEmpty()) 0 else sumOf { it.width } + spacingPx * (size - 1)

        val leading = measureSide(measurables.take(leadingCount))
        val trailing = measureSide(measurables.drop(leadingCount))
        val side = maxOf(leading.span(), trailing.span())
        val width = (side * 2 + gapPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height =
            (leading + trailing)
                .maxOfOrNull { it.height }
                ?.coerceIn(constraints.minHeight, constraints.maxHeight)
                ?: constraints.minHeight
        layout(width, height) {
            var x = 0
            leading.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + spacingPx
            }
            x = width
            trailing.asReversed().forEach {
                x -= it.width
                it.placeRelative(x, (height - it.height) / 2)
                x -= spacingPx
            }
        }
    }
}

private val CameraSplitSpacing = 8.dp
private val CompactUtilityStartTextWidth = 36.dp
private const val AlarmCountdownTickMs = 30_000L
private const val MaxPageDots = 5
private val PageDotSize = 4.dp
private val PageDotActiveWidth = 10.dp
private val PageDotSpacing = 3.dp
private val PageDotsTapPadding = 4.dp
internal val CallExtraHeight = 10.dp
internal val CallMinWidth = 150.dp
private val MinDynamicIslandWidth = 80.dp
private val MaxDynamicIslandWidth = 200.dp
private val UtilitySideChromeWidth = 36.dp
private val UtilityTextMinWidth = 48.dp
private val CompactUtilityCameraSplitMaxWidth = 232.dp
private val DynamicIslandEmbeddedGapFallbackWidth = 38.dp
private val DynamicIslandEmbeddedGapMinWidth = 34.dp
private val DynamicIslandEmbeddedGapMaxWidth = 88.dp
private val DynamicIslandEmbeddedGapSidePadding = 10.dp

data class DynamicIslandCutoutSpec(
    val embeddedGapWidth: Dp,
    val horizontalOffset: Dp,
    /** The camera's centre in window pixels, or null with no top cutout. */
    val cutoutCenterX: Float? = null,
    val verticalOffset: Dp,
)

@Composable
fun rememberDynamicIslandCutoutSpec(): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    val view = LocalView.current
    val verticalOffsetPercent = rememberDynamicIslandVerticalOffsetPercent()
    val statusBarHeight = dimensionResource(R.dimen.status_bar_height)
    var cameraAlignOffsetYPx by remember { mutableFloatStateOf(0f) }
    DisposableEffect(view) {
        val listener =
            View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val hole =
                    (v.rootWindowInsets?.displayCutout ?: v.display?.cutout)
                        ?.dynamicIslandHoleRect()
                cameraAlignOffsetYPx =
                    dynamicIslandCameraCenterOffsetYPx(
                        v,
                        hole,
                        v.resources.displayMetrics.density,
                    )
            }
        view.addOnLayoutChangeListener(listener)
        listener.onLayoutChange(view, 0, 0, 0, 0, 0, 0, 0, 0)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    val displayCutout = view.rootWindowInsets?.displayCutout ?: view.display?.cutout
    val topCutout = displayCutout?.topBoundingRectOrNull()
    val rootWidthPx =
        when {
            view.rootView.width > 0 -> view.rootView.width
            view.width > 0 -> view.width
            else -> view.resources.configuration.windowConfiguration.maxBounds.width()
        }

    return with(density) {
        // Percent of the status bar, so the same setting is the same fraction of the bar at
        // every density and resolution.
        // The user's offset stays "extra". The camera alignment is added underneath it.
        val verticalOffsetDp =
            statusBarHeight * (verticalOffsetPercent / 100f) + cameraAlignOffsetYPx.toDp()
        if (topCutout == null || rootWidthPx <= 0) {
            DynamicIslandCutoutSpec(
                embeddedGapWidth = DynamicIslandEmbeddedGapFallbackWidth,
                horizontalOffset = 0.dp,
                verticalOffset = verticalOffsetDp,
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
                verticalOffset = verticalOffsetDp,
            )
        }
    }
}

/**
 * Spec for an island docked to a camera on a side edge. The pill is laid out in its own rotated
 * frame, where its main axis runs along the edge, so the camera gap is the hole's height. A null
 * [edge] is unused; callers always invoke this so the composition stays stable when docking.
 */
@Composable
fun rememberDynamicIslandSideCutoutSpec(
    edge: DynamicIslandCutoutEdge?
): DynamicIslandCutoutSpec {
    val density = LocalDensity.current
    return remember(edge, density) {
        if (edge == null) {
            DynamicIslandCutoutSpec(
                embeddedGapWidth = DynamicIslandEmbeddedGapFallbackWidth,
                horizontalOffset = 0.dp,
                verticalOffset = 0.dp,
            )
        } else {
            with(density) {
                val embeddedGapWidthDp =
                    (edge.hole.height().toDp() + (DynamicIslandEmbeddedGapSidePadding * 2))
                        .coerceIn(
                            DynamicIslandEmbeddedGapMinWidth,
                            DynamicIslandEmbeddedGapMaxWidth,
                        )
                DynamicIslandCutoutSpec(
                    embeddedGapWidth = embeddedGapWidthDp,
                    horizontalOffset = 0.dp,
                    verticalOffset = 0.dp,
                )
            }
        }
    }
}

/**
 * The top cutout's extent, from its path when there is one. Some devices' bounding rects do not
 * match their cutout (phone2 reports 454-540 for a camera drawn at 511-569), and the island has
 * to sit on the camera itself.
 */
internal fun DisplayCutout.dynamicIslandHoleRect(): Rect? = topBoundingRectOrNull()

/**
 * The camera hole inside [boundingRect], a bounding rect of this cutout on a side edge. The top
 * edge keeps [dynamicIslandHoleRect], whose path bounds are not clipped to the reported rect,
 * because some devices' top rect does not contain the camera.
 */
fun DisplayCutout.dynamicIslandHoleWithin(boundingRect: Rect): Rect {
    val path = cutoutPath ?: return Rect(boundingRect)
    val pathBounds = RectF()
    path.computeBounds(pathBounds, true)
    if (pathBounds.isEmpty) return Rect(boundingRect)
    val hole = Rect()
    pathBounds.roundOut(hole)
    return if (Rect.intersects(hole, boundingRect)) hole else Rect(boundingRect)
}

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

@Composable
private fun rememberDynamicIslandVerticalOffsetPercent(): Int {
    val context = LocalContext.current
    val verticalOffsetPercent by
        remember { observeDynamicIslandVerticalOffset(context) }
            .collectAsState(initial = 0)
    return verticalOffsetPercent
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
