/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.panel.component.appvolume.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.PlatformSliderDefaults
import com.android.systemui.common.ui.compose.Icon as SysUiIcon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.appvolume.ui.viewmodel.AppVolumePanelViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AppVolumeSliderViewModel
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSlider
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderColors

private const val AppVolumePanelTestTag = "AppVolumePanel"
private val PanelPadding = 24.dp
private val AppIconSize = 40.dp
private val ListMaxHeight = 420.dp

@Composable
fun AppVolumePanelRoot(
    viewModel: AppVolumePanelViewModel,
    modifier: Modifier = Modifier,
) {
    val accessibilityTitle = stringResource(R.string.app_volume)
    val sliderViewModels by viewModel.sliderViewModels.collectAsStateWithLifecycle()
    val activeCount = sliderViewModels.size

    Column(
        modifier =
            modifier
                .sysuiResTag(AppVolumePanelTestTag)
                .semantics { paneTitle = accessibilityTitle }
                .padding(
                    start = PanelPadding,
                    top = PanelPadding,
                    end = PanelPadding,
                    bottom = 20.dp,
                )
                .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = accessibilityTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    if (activeCount == 0) {
                        stringResource(R.string.app_volume_subtitle_idle)
                    } else {
                        pluralStringResource(
                            R.plurals.app_volume_subtitle_active,
                            activeCount,
                            activeCount,
                        )
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sliderViewModels.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.weight(weight = 1f, fill = false).heightIn(max = ListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = sliderViewModels,
                    key = { sliderViewModel -> sliderViewModel.packageName },
                ) { sliderViewModel ->
                    AppVolumeSliderItem(
                        sliderViewModel = sliderViewModel,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformOutlinedButton(onClick = viewModel::onSettingsClicked) {
                Text(text = stringResource(R.string.volume_panel_dialog_settings_button))
            }
            PlatformButton(onClick = viewModel::onDoneClicked) {
                Text(text = stringResource(R.string.inline_done_button))
            }
        }
    }
}

@Composable
private fun AppVolumeSliderItem(
    sliderViewModel: AppVolumeSliderViewModel,
    modifier: Modifier = Modifier,
) {
    val sliderState by sliderViewModel.slider.collectAsStateWithLifecycle()
    val appIcon by sliderViewModel.appIcon.collectAsStateWithLifecycle()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(AppIconSize), contentAlignment = Alignment.Center) {
            appIcon?.let { icon ->
                SysUiIcon(
                    icon = icon,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(AppIconSize),
                )
            }
        }
        VolumeSlider(
            modifier = Modifier.weight(1f),
            state = sliderState,
            onValueChange = { newValue -> sliderViewModel.onValueChanged(sliderState, newValue) },
            onValueChangeFinished = { sliderViewModel.onValueChangeFinished() },
            onIconTapped = { sliderViewModel.toggleMuted(sliderState) },
            sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
            hapticsViewModelFactory = sliderViewModel.getSliderHapticsViewModelFactory(),
            materialSliderColors = VolumeSliderColors.Defaults,
        )
    }
}

@Composable
private fun EmptyState() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = stringResource(R.string.app_volume_no_active_apps),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
