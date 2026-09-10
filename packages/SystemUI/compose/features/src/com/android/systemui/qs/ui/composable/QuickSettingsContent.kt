/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.ui.composable

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.lifecycle.compose.LifecycleStartEffect
import com.android.compose.animation.scene.ContentScope
import com.android.compose.gesture.gesturesDisabled
import com.android.compose.modifiers.thenIf
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.ui.compose.QsShadeComponentsColumn
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.panels.ui.model.QsShadeComponent
import com.android.systemui.qs.shared.ui.QuickSettings.Elements
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.flow.filterNotNull

@Composable
fun ContentScope.QuickSettingsContent(
    viewModel: QuickSettingsContainerViewModel,
    mediaInRow: Boolean,
    modifier: Modifier = Modifier,
    mediaSquishiness: () -> Float = { 1f },
) {
    val volumeSliderViewModel = rememberQsVolumeSliderViewModel(viewModel)
    QuickSettingsPanelLayout(
        brightness =
            @Composable {
                // Keep the Element composed whenever the slider belongs in QS so shared
                // transitions (Always in QQS) have a stable target size/offset from frame 0.
                if (viewModel.isBrightnessSliderVisible) {
                    var isBrightnessSliderInteractable by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        snapshotFlow { Elements.QuickSettingsContent.currentAlpha() }
                            .filterNotNull()
                            .collect { isBrightnessSliderInteractable = it >= .5f }
                    }
                    Element(modifier = Modifier, key = Elements.BrightnessSlider) {
                        BrightnessSliderContainer(
                            viewModel.brightnessSliderViewModel,
                            containerColors =
                                ContainerColors(
                                    Color.Transparent,
                                    ContainerColors.defaultContainerColor,
                                ),
                            modifier =
                                Modifier.padding(
                                        vertical =
                                            dimensionResource(id = R.dimen.qs_brightness_margin_top)
                                    )
                                    .thenIf(!isBrightnessSliderInteractable) {
                                        Modifier.gesturesDisabled()
                                    },
                        )
                    }
                }
            },
        volume =
            @Composable {
                if (viewModel.isVolumeSliderVisible && volumeSliderViewModel != null) {
                    Element(modifier = Modifier, key = Elements.VolumeSlider) {
                        QsVolumeSliderRow(
                            viewModel = volumeSliderViewModel,
                            onSettingsClicked = {
                                viewModel.detailsViewModel.onVolumeSettingsButtonClicked(
                                    viewModel.audioDetailsViewModelFactory.create()
                                )
                            },
                        )
                    }
                }
            },
        tiles =
            @Composable {
                var listening by remember { mutableStateOf(false) }
                LifecycleStartEffect(Unit) {
                    listening = true

                    onStopOrDispose { listening = false }
                }

                Box {
                    GridAnchor()
                    TileGrid(
                        viewModel.tileGridViewModel,
                        listening = { listening },
                        modifier = Modifier.element(Elements.QuickSettingsTiles),
                    )
                }
            },
        media =
            @Composable {
                // Keep a player-sized slot even before the carousel measures so the footer
                // is not covered and then pushed down a frame later.
                val mediaSlotModifier =
                    if (viewModel.showMedia) {
                        Modifier.requiredHeightIn(min = Media.DEFAULT_HEIGHT)
                    } else {
                        Modifier
                    }
                if (isAlwaysComposedContentVisible()) {
                    Element(key = Media.Elements.MediaCarousel, modifier = mediaSlotModifier) {
                        Media(
                            viewModelFactory = viewModel.mediaViewModelFactory,
                            presentationStyle = MediaPresentationStyle.Default,
                            behavior = QuickSettingsContainerViewModel.mediaUiBehavior,
                            onDismissed = viewModel::onMediaSwipeToDismiss,
                            mediaSquishiness = mediaSquishiness,
                            location = Media.Location.QS,
                        )
                    }
                } else {
                    // Add an empty box when QS content is not visible to keep the same number of
                    // elements.
                    Box(modifier = mediaSlotModifier)
                }
            },
        mediaInRow = mediaInRow,
        components = viewModel.shadeComponents,
        modifier =
            modifier
                .element(Elements.QuickSettingsContent)
                .padding(horizontal = dimensionResource(id = R.dimen.qs_horizontal_margin))
                .sysuiResTag("quick_settings_panel"),
    )
}

@Composable
private fun QuickSettingsPanelLayout(
    brightness: @Composable () -> Unit,
    tiles: @Composable () -> Unit,
    media: @Composable () -> Unit,
    mediaInRow: Boolean,
    components: List<QsShadeComponent>,
    modifier: Modifier = Modifier,
    volume: @Composable () -> Unit = {},
) {
    QsShadeComponentsColumn(
        components = components,
        brightness = brightness,
        tiles = tiles,
        media = media,
        volume = volume,
        mediaInRow = mediaInRow,
        verticalArrangement = spacedBy(QuickSettingsShade.Dimensions.VerticalPadding),
        horizontalArrangement = spacedBy(QuickSettingsShade.Dimensions.HorizontalPadding),
        modifier = modifier,
    )
}
