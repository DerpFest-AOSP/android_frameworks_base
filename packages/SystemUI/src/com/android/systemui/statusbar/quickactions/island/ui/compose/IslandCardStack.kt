/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipId
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupContentModel
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The island opens every live activity as a stack once at least this many are running. */
internal const val ISLAND_STACK_MIN_CARDS = 2

private val CardEase = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

/**
 * Live activities shown as stacked cards: newest kinds on top, the player last, as on COS.
 * Transient system events keep the single-card popup.
 */
internal fun islandCardStack(chips: List<PopupChipModel.Shown>): List<PopupChipModel.Shown> {
    val withPopup = chips.filter { it.popupContent != PopupContentModel.None }
    val (media, others) = withPopup.partition { it.chipId == PopupChipId.MediaControl }
    return others.asReversed() + media
}

/**
 * Every live activity as its own card under the island. The top card comes out of the island
 * with the popup; the others slide out from under it one after another, and slide back on close.
 * Swipe a card sideways to put that activity away.
 */
@Composable
internal fun IslandCardStack(
    chips: List<PopupChipModel.Shown>,
    isVisible: Boolean,
    card: @Composable (PopupChipModel.Shown) -> Unit,
) {
    val config = LocalConfiguration.current
    val cardWidth = min(config.screenWidthDp.dp - StackSideMargin * 2, StackMaxWidth)
    val maxHeight = max(config.screenHeightDp.dp - StackVerticalClearance, StackMinHeight)

    val liveIds = chips.map { it.chipId }
    val models = remember { HashMap<PopupChipId, PopupChipModel.Shown>() }
    chips.forEach { models[it.chipId] = it }
    val openedWith = remember { liveIds.toSet() }
    val rendered = remember { mutableStateListOf<PopupChipId>().apply { addAll(liveIds) } }
    LaunchedEffect(liveIds) {
        val merged = liveIds.toMutableList()
        rendered.forEachIndexed { index, id ->
            if (id !in merged) merged.add(index.coerceAtMost(merged.size), id)
        }
        if (merged != rendered.toList()) {
            rendered.clear()
            rendered.addAll(merged)
        }
    }
    val currentChips by rememberUpdatedState(chips)
    val scroll = rememberScrollState()
    LaunchedEffect(isVisible) { if (isVisible) scroll.scrollTo(0) }
    Column(
        modifier =
            Modifier.width(cardWidth)
                .heightIn(max = maxHeight)
                .fadeScrolledEdge(scroll)
                .verticalScroll(scroll)
    ) {
        rendered.forEachIndexed { index, id ->
            key(id) {
                val chip = models[id]
                if (chip != null) {
                    StackedCard(
                        chip = chip,
                        index = index,
                        entering = id !in openedWith,
                        present = id in liveIds,
                        stackVisible = isVisible,
                        onRemoved = { rendered.remove(id) },
                        onDismiss = {
                            if (chip.isPopupShown) {
                                currentChips.firstOrNull { it.chipId != id }?.showPopup?.invoke()
                            }
                            chip.hide()
                        },
                        card = card,
                    )
                }
            }
        }
    }
}

@Composable
private fun StackedCard(
    chip: PopupChipModel.Shown,
    index: Int,
    entering: Boolean,
    present: Boolean,
    stackVisible: Boolean,
    onRemoved: () -> Unit,
    onDismiss: () -> Unit,
    card: @Composable (PopupChipModel.Shown) -> Unit,
) {
    val visibleState = remember { MutableTransitionState(!entering) }
    visibleState.targetState = present
    LaunchedEffect(visibleState.isIdle, visibleState.currentState, present) {
        if (visibleState.isIdle && !visibleState.currentState && !present) onRemoved()
    }

    val open = remember { Animatable(if (entering || index == 0) 1f else 0f) }
    LaunchedEffect(stackVisible) {
        if (stackVisible) {
            delay(StackStaggerBaseMs + index * StackStaggerMs)
            open.animateTo(1f, tween(StackCardInMs, easing = CardEase))
        } else if (index > 0) {
            open.animateTo(0f, tween(StackCardOutMs, easing = CardEase))
        }
    }

    var topInStack by remember { mutableFloatStateOf(0f) }
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var cardWidth by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visibleState = visibleState,
        modifier =
            Modifier.zIndex(-index.toFloat()).onPlaced { topInStack = it.positionInParent().y },
        enter =
            expandVertically(spring(dampingRatio = 0.9f, stiffness = 380f), Alignment.Top) +
                fadeIn(tween(220, delayMillis = 80)) +
                scaleIn(tween(320, easing = CardEase), initialScale = 0.92f),
        exit =
            shrinkVertically(tween(320, easing = CardEase), Alignment.Top) +
                fadeOut(tween(160)) +
                scaleOut(tween(260, easing = CardEase), targetScale = 0.92f),
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(bottom = StackGap)
                    .onSizeChanged { cardWidth = it.width.toFloat() }
                    .graphicsLayer {
                        val f = open.value
                        translationY = -topInStack * (1f - f)
                        val scale = StackTuckedScale + (1f - StackTuckedScale) * f
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationX = swipe.value
                        val swipeFade = if (cardWidth > 0f) 1f - abs(swipe.value) / cardWidth else 1f
                        alpha = (f * 1.6f).coerceAtMost(1f) * swipeFade.coerceIn(0f, 1f)
                    }
                    .pointerInput(chip.chipId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val width = size.width.toFloat()
                                val offset = swipe.value
                                scope.launch {
                                    if (abs(offset) > width * StackDismissFraction) {
                                        swipe.animateTo(sign(offset) * width, tween(180, easing = CardEase))
                                        currentOnDismiss()
                                    } else {
                                        swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f))
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { swipe.animateTo(0f, spring(stiffness = 500f)) }
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                scope.launch { swipe.snapTo(swipe.value + amount) }
                            },
                        )
                    },
            propagateMinConstraints = true,
        ) {
            card(chip)
        }
    }
}

/** Fades the bottom edge out while more cards are below it, instead of cutting a card off. */
private fun Modifier.fadeScrolledEdge(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (scroll.value < scroll.maxValue) {
                val fade = StackFadeEdge.toPx().coerceAtMost(size.height / 2f)
                drawRect(
                    brush =
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black,
                            startY = size.height - fade,
                            endY = size.height,
                        ),
                    blendMode = BlendMode.DstOut,
                )
            }
        }

private val StackFadeEdge = 48.dp
private val StackSideMargin = 16.dp
private val StackMaxWidth = 420.dp
private val StackGap = 8.dp
private val StackVerticalClearance = 96.dp
private val StackMinHeight = 160.dp
private const val StackTuckedScale = 0.9f
private const val StackDismissFraction = 0.3f
private const val StackStaggerBaseMs = 90L
private const val StackStaggerMs = 45L
private const val StackCardInMs = 380
private const val StackCardOutMs = 260
