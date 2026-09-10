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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Trace
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.trace
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.mechanics.compose.modifier.verticalFadeContentReveal
import com.android.mechanics.compose.modifier.verticalTactileSurfaceReveal
import com.android.mechanics.effects.VerticalTactileSurfaceRevealEffect
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.TransitionAnimator.Companion.dynamicTargetResolutionEnabled
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.Tooltip
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveIconCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.ActiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveIconCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveTileCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelMoreDetails
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.tiles.impl.ringer.QSTileRingerSlider
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.qs.ui.compose.qsGradientBrush
import com.android.systemui.qs.ui.compose.rememberContrastColorOn
import com.android.systemui.qs.ui.compose.rememberQsGradientColors
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

private val TileViewModel.traceName
    get() = spec.toString().takeLast(Trace.MAX_SECTION_NAME_LEN)

/** [Settings.Secure] key selecting card tiles (0) or classic circular tiles (1). */
private const val QS_PANEL_STYLE = "qs_panel_style"

/** [Settings.Secure] key hiding the label underneath classic circular tiles. */
private const val QS_TILE_LABEL_HIDE = "qs_tile_label_hide"

/** [Settings.Secure] key naming the [QSTileIconShapes] shape used by classic tiles. */
private const val QS_TILE_ICON_SHAPE = "qs_tile_icon_shape"

/** [Settings.Secure] key selecting the tile toggle animation style (0 = off). */
private const val QS_TILE_ANIMATION_STYLE = "qs_tile_animation_style"

/**
 * This composable function is responsible for rendering a tile based on the provided
 * [TileViewModel]. It handles different states of the tile (e.g., available, unavailable),
 * interactions (click, long click), and visual styles (icon only or large tile).
 *
 * @param tile The [TileViewModel] containing the data and logic for the tile.
 * @param iconOnly A boolean indicating whether to display only the icon of the tile or the full
 *   tile content (false for large tiles).
 * @param squishiness The float value representing the current squishiness factor of the tile, used
 *   for animations.
 * @param coroutineScope The [CoroutineScope] to launch coroutines for animations.
 * @param tileHapticsViewModelFactory A factory for creating a [TileHapticsViewModel] instance, used
 *   for haptic feedback.
 * @param modifier An optional [Modifier] to be applied to the root composable of the tile.
 * @param isVisible Whether the tile is currently visible. Defaults to true.
 * @param requestToggleTextFeedback A lambda function that is invoked when a toggleable icon only
 *   tile is clicked, used to request the feedback text.
 * @param detailsViewModel An optional [DetailsViewModel] used to handle navigation to a detailed
 *   view when a tile is clicked, if applicable.
 * @param enableRevealEffect If `true`, the tiles will animate using the reveal animation.
 */
@Composable
fun ContentScope.Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    bounceableInfo: BounceableInfo,
    tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    requestToggleTextFeedback: (TileSpec) -> Unit = {},
    detailsViewModel: DetailsViewModel?,
    enableRevealEffect: Boolean = false,
) {
    trace(tile.traceName) {
        val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
        val resources = resources()

        /*
         * Use produce state because [QSTile.State] doesn't have well defined equals (due to
         * inheritance). This way, even if tile.state changes, uiState may not change and lead to
         * recomposition.
         */
        val uiState by
            produceState(tile.currentState.toUiState(resources), tile, resources) {
                tile.state.collect { value = it.toUiState(resources) }
            }
        val isClickable = uiState.handlesMainClick

        val icon by
            produceState(tile.currentState.toIconProvider(), tile) {
                tile.state.collect { value = it.toIconProvider() }
            }

        val colors = TileDefaults.getColorForState(uiState, iconOnly)
        val hapticsViewModel: TileHapticsViewModel =
            rememberViewModel(traceName = "TileHapticsViewModel") {
                tileHapticsViewModelFactory.create(tile)
            }

        if (tile.spec.spec == "sound" && !iconOnly) {
            QSTileRingerSlider()
            return@trace
        }

        val classicStyle = rememberQSPanelStyle()
        val iconShapeKey = rememberQSTileIconShapeKey()
        val labelHide = classicStyle && rememberQSTileLabelHide()
        val tileAnimationStyle = rememberQSTileAnimationStyle()
        val tileHeight = if (classicStyle && !labelHide) TileHeight + 8.dp else TileHeight

        val shapeMode = rememberTileShapeMode()
        val wantCircle = shapeMode == 3 && iconOnly

        // TODO(b/361789146): Draw the shapes instead of clipping
        val tileShape by TileDefaults.animateTileShapeAsState(uiState, shapeMode)
        val animatedColor by animateColorAsState(colors.background, label = "QSTileBackgroundColor")
        val isDualTarget = uiState.handlesToggleClick
        val interactionSource = remember { MutableInteractionSource() }

        // When a full circle is requested, the outer expandable container is made invisible
        // (transparent, unrounded) and a separate circular clickable is drawn centered within it.
        val backgroundBrush = colors.backgroundBrush
        val outerShape: RoundedCornerShape =
            if (wantCircle && !classicStyle) RoundedCornerShape(0.dp) else tileShape
        val outerColor: () -> Color =
            when {
                wantCircle || classicStyle -> ({ Color.Transparent })
                backgroundBrush != null -> ({ Color.Transparent })
                else -> ({ animatedColor })
            }

        val surfaceRevealModifier: Modifier
        val contentRevealModifier: Modifier
        if (enableRevealEffect) {
            val marginBottom =
                with(LocalDensity.current) { QuickSettingsShade.Dimensions.VerticalPadding.toPx() }

            val animatedCornerRadius by animateDpAsState(TileDefaults.tileRadius(uiState, shapeMode))

            val inactiveCornerRadius = InactiveIconCornerRadius
            surfaceRevealModifier =
                Modifier.verticalTactileSurfaceReveal(
                    deltaY = marginBottom,
                    effectSpec =
                        remember(inactiveCornerRadius, shapeMode) {
                            VerticalTactileSurfaceRevealEffect(
                                maxCornerSize = { animatedCornerRadius },
                                phase1MarginX = inactiveCornerRadius,
                            )
                        },
                    label = tile.traceName,
                )

            contentRevealModifier =
                Modifier.verticalFadeContentReveal(deltaY = marginBottom, label = tile.traceName)
        } else {
            surfaceRevealModifier = Modifier
            contentRevealModifier = Modifier
        }

        val expandable =
            if (dynamicTargetResolutionEnabled()) tile.expandable
            else remember { Expandable(mutableSetOf()) }
        val focusBorderColor = MaterialTheme.colorScheme.secondary
        Tooltip(
            text = uiState.label,
            modifier = modifier,
            enabled = Flags.enableQsTileTooltips(),
        ) { modifier ->
            TileExpandable(
                expandable = expandable,
                color = outerColor,
                shape = outerShape,
                squishiness = squishiness,
                hapticsViewModel = hapticsViewModel,
                classicStyle = classicStyle,
                modifier =
                    modifier
                        .then(surfaceRevealModifier)
                        .thenIf(backgroundBrush != null && !wantCircle && !classicStyle) {
                            Modifier.background(requireNotNull(backgroundBrush), outerShape)
                        }
                        .thenIf(!wantCircle) {
                            Modifier.borderOnFocus(
                                color = focusBorderColor,
                                outerShape.topEnd,
                            )
                        }
                        .sysuiResTag("tile_expandable")
                        .fillMaxWidth()
                        .tileToggleAnimation(uiState.visualState, tileAnimationStyle)
                        // Pin height for circle/classic; otherwise QQS max-height stretches rows.
                        .thenIf(classicStyle || wantCircle) { Modifier.height(tileHeight) }
                        .bounceable(
                            currentBounceableInfo.bounceable,
                            currentBounceableInfo.previousTile,
                            currentBounceableInfo.nextTile,
                            orientation = Orientation.Horizontal,
                            bounceEnd = currentBounceableInfo.bounceEnd,
                            interactionSource = interactionSource,
                        ),
            ) { expandable ->
                // Use main click on long press for small, available dual target tiles.
                // Open settings otherwise.
                val useLongClickToSettings = !(iconOnly && isDualTarget && isClickable)
                val longClick: (() -> Unit)? =
                    {
                            hapticsViewModel.setTileInteractionState(
                                TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                            )

                            if (useLongClickToSettings) {
                                tile.settingsClick(expandable)
                            } else {
                                val hasDetails =
                                    QsDetailedView.isEnabled &&
                                        detailsViewModel?.onTileClicked(tile.spec) == true
                                if (!hasDetails) {
                                    tile.mainClick(expandable)
                                }
                            }
                        }
                        .takeIf { !useLongClickToSettings || uiState.handlesSettingsClick }

                // Bounce the tile's container if it is toggleable and is not a large
                // dual target tile. These don't toggle on main click.
                val bounceContainer = uiState.isToggleable && (iconOnly || !isDualTarget)
                val click: (() -> Unit)? = onClick@{
                    if (!isClickable) return@onClick

                    if (iconOnly && isDualTarget) {
                        tile.toggleClick()
                    } else {
                        val hasDetails =
                            QsDetailedView.isEnabled &&
                                detailsViewModel?.onTileClicked(tile.spec) == true
                        if (hasDetails) return@onClick

                        // For those tile's who doesn't have a detailed view, process with
                        // their `onClick` behavior.
                        tile.mainClick(expandable)
                    }

                    // Side effects of the click
                    hapticsViewModel.setTileInteractionState(
                        TileHapticsViewModel.TileInteractionState.CLICKED
                    )

                    coroutineScope.launch {
                        // Bounce the content of the tile if we're not animating the
                        // container.
                        if (!bounceContainer) {
                            currentBounceableInfo.bounceable.animateContentBounce(iconOnly)
                        }
                    }
                    if (uiState.isToggleable && iconOnly) {
                        // And show footer text feedback for icons
                        requestToggleTextFeedback(tile.spec)
                    }
                }

                if (wantCircle || classicStyle) {
                    // Draw a standalone circular clickable centered in the tile's bounds. The
                    // outer expandable container was made transparent/unrounded above, so this is
                    // the only visible/clickable surface for the tile. Classic tiles paint their
                    // own badge in [ClassicTileContent], so the container stays undecorated.
                    val circleInteraction = remember { MutableInteractionSource() }
                    Box(Modifier.fillMaxWidth().height(tileHeight)) {
                        Box(
                            modifier =
                                Modifier.size(tileHeight)
                                    .align(Alignment.Center)
                                    .thenIf(!classicStyle) {
                                        Modifier.clip(CircleShape)
                                            .then(
                                                if (backgroundBrush != null) {
                                                    Modifier.background(backgroundBrush)
                                                } else {
                                                    Modifier.background(animatedColor)
                                                }
                                            )
                                    }
                                    .indication(circleInteraction, LocalIndication.current)
                                    .tileCombinedClickable(
                                        onClick = { click?.invoke() },
                                        onLongClick = longClick,
                                        accessibilityUiState = uiState.accessibilityUiState,
                                        interactionSource =
                                            interactionSource.takeIf { bounceContainer }
                                                ?: circleInteraction,
                                        iconOnly = true,
                                        isDualTarget = isDualTarget,
                                    )
                                    .tileTestTag(iconOnly)
                        ) {
                            val iconProvider: Context.() -> Icon = { getTileIcon(icon = icon) }
                            if (classicStyle) {
                                ClassicTileContent(
                                    label = uiState.label,
                                    iconProvider = iconProvider,
                                    iconShapeKey = iconShapeKey,
                                    colors = colors,
                                    labelHide = labelHide,
                                    tileState = uiState.visualState,
                                    modifier =
                                        Modifier.align(Alignment.Center).bounceScale {
                                            currentBounceableInfo.bounceable.iconBounceScale
                                        },
                                )
                            } else {
                                SmallTileContent(
                                    iconProvider = iconProvider,
                                    color = colors.icon,
                                    modifier =
                                        Modifier.align(Alignment.Center).bounceScale {
                                            currentBounceableInfo.bounceable.iconBounceScale
                                        },
                                )
                            }
                        }
                    }
                } else {
                    TileContainer(
                        interactionSource = interactionSource.takeIf { bounceContainer },
                        onClick = click,
                        onLongClick = longClick,
                        accessibilityUiState = uiState.accessibilityUiState,
                        iconOnly = iconOnly,
                        isDualTarget = isDualTarget,
                        modifier = contentRevealModifier,
                    ) {
                        val iconProvider: Context.() -> Icon = { getTileIcon(icon = icon) }
                        if (iconOnly) {
                            SmallTileContent(
                                iconProvider = iconProvider,
                                color = colors.icon,
                                modifier =
                                    Modifier.align(Alignment.Center).bounceScale {
                                        currentBounceableInfo.bounceable.iconBounceScale
                                    },
                            )
                        } else {
                            val iconShape by TileDefaults.animateIconShapeAsState(uiState, shapeMode)
                            val secondaryClick: (() -> Unit)? =
                                {
                                        hapticsViewModel.setTileInteractionState(
                                            TileHapticsViewModel.TileInteractionState.CLICKED
                                        )
                                        tile.toggleClick()
                                    }
                                    .takeIf { isDualTarget }
                            LargeTileContent(
                                label = uiState.label,
                                secondaryLabel = uiState.secondaryLabel,
                                iconProvider = iconProvider,
                                sideDrawable = uiState.sideDrawable,
                                colors = colors,
                                iconShape = iconShape,
                                toggleClick = secondaryClick,
                                onLongClick = longClick,
                                accessibilityUiState = uiState.accessibilityUiState,
                                squishiness = squishiness,
                                isVisible = isVisible,
                                textScale = { currentBounceableInfo.bounceable.textBounceScale },
                                modifier =
                                    Modifier.largeTilePadding(
                                        isDualTarget = uiState.handlesSettingsClick
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileExpandable(
    expandable: Expandable,
    color: () -> Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel?,
    classicStyle: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        expandable = expandable,
        controller = rememberExpandableController(color = color, shape = shape),
        modifier =
            modifier
                .thenIf(!classicStyle) { Modifier.clip(shape) }
                .motionTestValues { squishiness() exportAs TileMotionTestKeys.Squishness }
                .verticalSquish(squishiness),
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel?.createStateAwareExpandable(it) ?: it)
    }
}

@Composable
fun TileContainer(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    iconOnly: Boolean,
    isDualTarget: Boolean,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(TileHeight)
                .fillMaxWidth()
                .tileCombinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    accessibilityUiState = accessibilityUiState,
                    iconOnly = iconOnly,
                    isDualTarget = isDualTarget,
                    interactionSource = interactionSource,
                )
                .tileTestTag(iconOnly),
        content = content,
    )
}

@Composable
fun SmallStaticTile(
    uiState: TileUiState,
    iconProvider: IconProvider,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val shapeMode = rememberTileShapeMode()
    val colors = TileDefaults.getColorForState(uiState = uiState, iconOnly = true)

    Box(
        modifier
            .clip(TileDefaults.animateTileShapeAsState(uiState, shapeMode).value)
            .background(colors.background)
            .size(TileHeight)
            .clickable(onClick = onClick)
    ) {
        SmallTileContent(
            iconProvider = { getTileIcon(icon = iconProvider) },
            color = colors.icon,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
fun LargeStaticTile(
    uiState: TileUiState,
    iconProvider: IconProvider,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val shapeMode = rememberTileShapeMode()
    val colors = TileDefaults.getColorForState(uiState = uiState, iconOnly = false)

    Box(
        modifier
            .clip(TileDefaults.animateTileShapeAsState(uiState, shapeMode).value)
            .background(colors.background)
            .height(TileHeight)
            .clickable(onClick = onClick)
            .largeTilePadding()
    ) {
        LargeTileContent(
            label = uiState.label,
            secondaryLabel = "",
            iconProvider = { getTileIcon(icon = iconProvider) },
            sideDrawable = null,
            colors = colors,
            squishiness = { 1f },
        )
    }
}

private fun Context.getTileIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(space = CommonTileDefaults.TileArrangementPadding, alignment = Alignment.Start)
}

@Composable
fun Modifier.tileCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    interactionSource: MutableInteractionSource?,
    iconOnly: Boolean,
    isDualTarget: Boolean,
): Modifier {
    val longPressLabel =
        if (iconOnly && isDualTarget) longPressLabelMoreDetails() else longPressLabelSettings()
    return combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = accessibilityUiState.clickLabel,
            onLongClickLabel = longPressLabel,
            hapticFeedbackEnabled = false, // Haptics handled separately
            interactionSource = interactionSource,
        )
        .semantics {
            val accessibilityRole =
                if (iconOnly && isDualTarget) {
                    Role.Switch
                } else {
                    accessibilityUiState.accessibilityRole
                }
            if (accessibilityRole == Role.Switch) {
                accessibilityUiState.toggleableState?.let { toggleableState = it }
            }
            role = accessibilityRole
            stateDescription = accessibilityUiState.stateDescription
        }
        .thenIf(iconOnly) {
            Modifier.semantics { contentDescription = accessibilityUiState.contentDescription }
        }
}

data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
    val backgroundBrush: Brush? = null,
    val iconBackgroundBrush: Brush? = null,
    val outline: Color,
)

@VisibleForTesting
object TileMotionTestKeys {
    val Squishness = MotionTestValueKey<Float>("tile_squishiness")
}

/**
 * Reads and observes [Settings.System.QS_TILE_SHAPE], which lets the user customize the shape of
 * QS tiles:
 * - 0 = default (state-based rounded corners)
 * - 1 = circle-ish (large corner radius)
 * - 2 = rounded square (active corner radius)
 * - 3 = full circle (icon-only tiles only)
 */
@Composable
fun rememberTileShapeMode(): Int {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readShapeMode(): Int {
        return try {
            Settings.System.getIntForUser(
                contentResolver,
                Settings.System.QS_TILE_SHAPE,
                0,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Throwable) {
            0
        }
    }

    var shapeMode by remember { mutableIntStateOf(readShapeMode()) }

    DisposableEffect(contentResolver) {
        // Scene-container QS can compose before Settings is ready; re-read on subscribe.
        shapeMode = readShapeMode()
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { shapeMode = readShapeMode() }
                }
            }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_TILE_SHAPE),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return shapeMode
}

/**
 * Reads and observes [Settings.Secure] `qs_panel_style`, which switches quick settings between the
 * default card tiles (0) and classic circular tiles (1).
 */
@Composable
fun rememberQSPanelStyle(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readPanelStyleEnabled(): Boolean {
        return try {
            Settings.Secure.getIntForUser(
                contentResolver,
                QS_PANEL_STYLE,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        } catch (_: Throwable) {
            false
        }
    }

    var classicStyleEnabled by remember { mutableStateOf(readPanelStyleEnabled()) }

    DisposableEffect(contentResolver) {
        // Scene-container QS can compose before Settings is ready; re-read on subscribe.
        classicStyleEnabled = readPanelStyleEnabled()
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { classicStyleEnabled = readPanelStyleEnabled() }
                }
            }

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(QS_PANEL_STYLE),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return classicStyleEnabled
}

/**
 * Reads and observes [Settings.Secure] `qs_tile_label_hide`, which drops the label underneath
 * classic circular tiles so they render as bare icons.
 */
@Composable
fun rememberQSTileLabelHide(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readLabelHideEnabled(): Boolean {
        return try {
            Settings.Secure.getIntForUser(
                contentResolver,
                QS_TILE_LABEL_HIDE,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        } catch (_: Throwable) {
            false
        }
    }

    var labelHideEnabled by remember { mutableStateOf(readLabelHideEnabled()) }

    DisposableEffect(contentResolver) {
        // Scene-container QS can compose before Settings is ready; re-read on subscribe.
        labelHideEnabled = readLabelHideEnabled()
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { labelHideEnabled = readLabelHideEnabled() }
                }
            }

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(QS_TILE_LABEL_HIDE),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return labelHideEnabled
}

/**
 * Reads and observes [Settings.Secure] `qs_tile_icon_shape`, naming the [QSTileIconShapes] shape
 * that classic tiles clip their icon badge to. Unknown values fall back to the default shape.
 */
@Composable
fun rememberQSTileIconShapeKey(): String {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readIconShapeKey(): String {
        return try {
            Settings.Secure.getStringForUser(
                    contentResolver,
                    QS_TILE_ICON_SHAPE,
                    UserHandle.USER_CURRENT,
                )
                ?.takeIf { QSTileIconShapes.isKnownKey(it) } ?: QSTileIconShapes.DEFAULT_KEY
        } catch (_: Throwable) {
            QSTileIconShapes.DEFAULT_KEY
        }
    }

    var iconShapeKey by remember { mutableStateOf(readIconShapeKey()) }

    DisposableEffect(contentResolver) {
        // Scene-container QS can compose before Settings is ready; re-read on subscribe.
        iconShapeKey = readIconShapeKey()
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { iconShapeKey = readIconShapeKey() }
                }
            }

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(QS_TILE_ICON_SHAPE),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return iconShapeKey
}

/**
 * Reads and observes [Settings.Secure] `qs_tile_animation_style`, which selects the animation
 * played when a quick settings tile toggles between active and inactive.
 */
@Composable
fun rememberQSTileAnimationStyle(): Int {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readAnimationStyle(): Int {
        return try {
            Settings.Secure.getIntForUser(
                contentResolver,
                QS_TILE_ANIMATION_STYLE,
                0,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Throwable) {
            0
        }
    }

    var animationStyle by remember { mutableStateOf(readAnimationStyle()) }

    DisposableEffect(contentResolver) {
        // Scene-container QS can compose before Settings is ready; re-read on subscribe.
        animationStyle = readAnimationStyle()
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute { animationStyle = readAnimationStyle() }
                }
            }

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(QS_TILE_ANIMATION_STYLE),
            false,
            observer,
            UserHandle.USER_ALL,
        )

        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return animationStyle
}

@Composable
fun rememberQsGradientEnabled(): Boolean {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readGradientEnabled(): Boolean {
        return try {
            Settings.System.getIntForUser(
                contentResolver, Settings.System.QS_TILE_GRADIENT_ENABLED, 1,
                UserHandle.USER_CURRENT
            ) == 1
        } catch (_: Throwable) {
            true
        }
    }

    var gradientEnabled by remember(contentResolver) { mutableStateOf(readGradientEnabled()) }

    DisposableEffect(contentResolver) {
        gradientEnabled = readGradientEnabled()
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute {
                    gradientEnabled = readGradientEnabled()
                }
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_TILE_GRADIENT_ENABLED),
            false, observer, UserHandle.USER_ALL
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    return gradientEnabled
}

private object TileDefaults {
    /** An active tile uses the active color as background */
    @Composable
    fun activeTileColors(): TileColors {
        val gradientEnabled = rememberQsGradientEnabled()
        val gradientColors = rememberQsGradientColors()
        val contrast = rememberContrastColorOn(gradientColors.mid)
        val gradient =
            remember(gradientEnabled, gradientColors.start, gradientColors.end) {
                if (gradientEnabled) {
                    qsGradientBrush(gradientColors.start, gradientColors.end)
                } else {
                    null
                }
            }
        val onGradient = if (gradientEnabled) contrast else MaterialTheme.colorScheme.onPrimary
        return TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = onGradient,
            secondaryLabel = onGradient,
            icon = onGradient,
            backgroundBrush = gradient,
            iconBackgroundBrush = gradient,
            outline = MaterialTheme.colorScheme.primary,
        )
    }

    /** An active tile with dual target only show the active color on the icon */
    @Composable
    fun activeDualTargetTileColors(): TileColors {
        val gradientEnabled = rememberQsGradientEnabled()
        val gradientColors = rememberQsGradientColors()
        val contrast = rememberContrastColorOn(gradientColors.start)
        val gradient =
            remember(gradientEnabled, gradientColors.start, gradientColors.end) {
                if (gradientEnabled) {
                    qsGradientBrush(gradientColors.start, gradientColors.end)
                } else {
                    null
                }
            }
        // Dual-target icon sits on the start (left) of the horizontal gradient.
        val onGradient = if (gradientEnabled) contrast else MaterialTheme.colorScheme.onPrimary
        return TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = onGradient,
            iconBackgroundBrush = gradient,
            outline = MaterialTheme.colorScheme.primary,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun inactiveDualTargetTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = LocalAndroidColorScheme.current.surfaceEffect2,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
            outline = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
            outline = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun unavailableTileColors(): TileColors {
        val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .18f)
        val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
        return TileColors(
            background = surfaceColor,
            iconBackground = surfaceColor,
            label = onSurfaceVariantColor,
            secondaryLabel = onSurfaceVariantColor,
            icon = onSurfaceVariantColor,
            outline = onSurfaceVariantColor,
        )
    }

    @Composable
    fun getColorForState(uiState: TileUiState, iconOnly: Boolean): TileColors {
        return when (uiState.visualState) {
            STATE_ACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    activeDualTargetTileColors()
                } else {
                    activeTileColors()
                }
            }

            STATE_INACTIVE -> {
                if (uiState.handlesToggleClick && !iconOnly) {
                    inactiveDualTargetTileColors()
                } else {
                    inactiveTileColors()
                }
            }

            else -> unavailableTileColors()
        }
    }

    @Composable
    fun iconRadius(uiState: TileUiState, shapeMode: Int): Dp {
        return when (shapeMode) {
            1,
            3 -> InactiveIconCornerRadius // Circle-ish / circle
            2 -> ActiveIconCornerRadius // Rounded square
            else ->
                when (uiState.visualState) {
                    STATE_ACTIVE -> ActiveIconCornerRadius
                    STATE_INACTIVE -> InactiveIconCornerRadius
                    else -> InactiveIconCornerRadius
                }
        }
    }

    @Composable
    fun tileRadius(uiState: TileUiState, shapeMode: Int): Dp {
        return when (shapeMode) {
            1,
            3 -> InactiveTileCornerRadius // Circle-ish / circle
            2 -> ActiveTileCornerRadius // Rounded square
            else ->
                when (uiState.visualState) {
                    STATE_ACTIVE -> ActiveTileCornerRadius
                    STATE_INACTIVE -> InactiveTileCornerRadius
                    else -> InactiveTileCornerRadius
                }
        }
    }

    @Composable
    fun animateIconShapeAsState(uiState: TileUiState, shapeMode: Int): State<RoundedCornerShape> {
        return animateShapeAsState(
            targetValue = iconRadius(uiState, shapeMode),
            label = "QSTileIconCornerRadius",
        )
    }

    @Composable
    fun animateTileShapeAsState(uiState: TileUiState, shapeMode: Int): State<RoundedCornerShape> {
        return animateShapeAsState(
            targetValue = tileRadius(uiState, shapeMode),
            label = "QSTileCornerRadius",
        )
    }

    @Composable
    fun animateShapeAsState(targetValue: Dp, label: String): State<RoundedCornerShape> {
        val animatedCornerRadius by animateDpAsState(targetValue = targetValue, label = label)
        val animatedCornerRadiusState = rememberUpdatedState(animatedCornerRadius)

        // New Shape when the target radius changes so clip/background caches update.
        return remember(targetValue) {
            val corner =
                object : CornerSize {
                    override fun toPx(shapeSize: Size, density: Density): Float {
                        return with(density) { animatedCornerRadiusState.value.toPx() }
                    }
                }
            mutableStateOf(RoundedCornerShape(corner))
        }
    }
}

/**
 * A composable function that returns the [Resources]. It will be recomposed when [Configuration]
 * gets updated.
 */
@Composable
@ReadOnlyComposable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}
