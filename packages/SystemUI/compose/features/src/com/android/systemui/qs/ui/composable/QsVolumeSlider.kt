/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSliderDefaults
import com.android.compose.modifiers.thenIf
import com.android.systemui.brightness.ui.compose.qsSliderButtonShape
import com.android.systemui.brightness.ui.compose.qsSliderTrackCornerSize
import com.android.systemui.brightness.ui.compose.rememberSliderShapeMode
import com.android.systemui.qs.ui.compose.rememberContrastColorOn
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.sliders.ui.compose.rememberVolumeSliderGradient
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSlider
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderDimensions

/** Volume slider and overflow button shown in Quick Settings. */
@Composable
fun rememberQsVolumeSliderViewModel(
    viewModel: QuickSettingsContainerViewModel
): AudioStreamSliderViewModel? {
    val coroutineScope = rememberCoroutineScope()
    return remember(coroutineScope, viewModel.showVolumeSlider) {
        viewModel.createVolumeSliderViewModel(coroutineScope)
    }
}

@Composable
fun QsVolumeSliderRow(
    viewModel: AudioStreamSliderViewModel,
    onSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
    dimensions: VolumeSliderDimensions = VolumeSliderDimensions.Defaults,
) {
    val volumeSliderState by viewModel.slider.collectAsStateWithLifecycle()
    val shapeMode = rememberSliderShapeMode()
    val trackCornerSize = qsSliderTrackCornerSize(shapeMode)
    val buttonShape = qsSliderButtonShape(shapeMode)
    val gradient = rememberVolumeSliderGradient(isVertical = false)
    val overflowContrastSample = gradient?.mid ?: Color.Transparent
    val overflowContrast = rememberContrastColorOn(overflowContrastSample)
    val overflowIconColor =
        if (gradient != null) overflowContrast else MaterialTheme.colorScheme.onPrimary

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VolumeSlider(
            modifier = Modifier.weight(1f),
            showLabel = false,
            state = volumeSliderState,
            onValueChange = { newValue: Float ->
                viewModel.onValueChanged(volumeSliderState, newValue)
            },
            onValueChangeFinished = { viewModel.onValueChangeFinished() },
            onIconTapped = { viewModel.toggleMuted(volumeSliderState) },
            sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
            hapticsViewModelFactory = viewModel.getSliderHapticsViewModelFactory(),
            dimensions = dimensions,
            trackCornerSize = trackCornerSize,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            modifier =
                Modifier.size(dimensions.trackHeight)
                    .thenIf(gradient != null) {
                        Modifier.background(requireNotNull(gradient).brush, buttonShape)
                    },
            shape = buttonShape,
            colors =
                IconButtonDefaults.iconButtonColors(
                    containerColor =
                        if (gradient != null) Color.Transparent
                        else MaterialTheme.colorScheme.primary,
                    contentColor = overflowIconColor,
                ),
            onClick = onSettingsClicked,
        ) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                // TODO(b/378513663): Update the placeholder content description
                contentDescription = "Volume settings",
                tint = overflowIconColor,
            )
        }
    }
}
