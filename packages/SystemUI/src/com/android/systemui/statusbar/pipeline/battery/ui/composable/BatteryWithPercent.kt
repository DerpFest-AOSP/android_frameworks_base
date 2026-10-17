/*
 * Copyright (C) 2025 crDroid Android Project
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

package com.android.systemui.statusbar.pipeline.battery.ui.composable

import android.graphics.Rect
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel

@Composable
fun BatteryWithPercent(
    viewModel: BatteryViewModel,
    isDarkProvider: () -> IsAreaDark,
    modifier: Modifier = Modifier,
    showPercent: Boolean = true,
    showEstimate: Boolean = false,
    /** When false (e.g. quick settings), do not apply accent tint even if setting is on */
    useAccentTintInContext: Boolean = true,
) {
    val ctx = LocalContext.current
    val themedIconHeightPx =
        ctx.resources.getDimensionPixelSize(R.dimen.config_batterymeterIconHeight)
    val batteryHeight =
        with(LocalDensity.current) {
            if (themedIconHeightPx > 0) themedIconHeightPx.toDp()
            else BatteryViewModel.getStatusBarBatteryHeight(ctx).toDp()
        }

    val textStyle =
        with(LocalDensity.current) {
            BatteryViewModel.getStatusBarBatteryTextStyle(LocalContext.current).copy(
                platformStyle = PlatformTextStyle(
                    includeFontPadding = false
                ),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                )
            )
        }

    var bounds by remember { mutableStateOf(Rect()) }

    val effectiveColorProfile =
        if (useAccentTintInContext) viewModel.colorProfile else viewModel.stateColorProfile

    // Use same color profile as the battery icon so text is tinted with accent when enabled
    val colorProducer = {
        if (isDarkProvider().isDarkTheme(bounds)) {
            effectiveColorProfile.dark.fill
        } else {
            effectiveColorProfile.light.fill
        }
    }

    Row(
        modifier =
            modifier.wrapContentWidth().onLayoutRectChanged { relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isTextStyle = viewModel.batteryIconStyle == BatteryRepository.ICON_STYLE_TEXT
        if (!isTextStyle) {
            UnifiedBattery(
                viewModel = viewModel,
                isDarkProvider = isDarkProvider,
                modifier = Modifier.height(batteryHeight).wrapContentWidth(),
                useAccentTintInContext = useAccentTintInContext,
            )
        }

        if (isTextStyle) {
            val percent = if (showPercent) viewModel.batteryPercent else null
            val attribution = viewModel.attribution
            if (percent != null || attribution != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (percent != null) {
                        BasicText(
                            text = percent,
                            color = colorProducer,
                            style = textStyle,
                            maxLines = 1,
                        )
                    }
                    if (attribution != null) {
                        val glyphHeight =
                            with(LocalDensity.current) { textStyle.fontSize.toDp() }
                        TextModeAttributionGlyph(
                            attr = attribution,
                            color = colorProducer,
                            modifier =
                                Modifier.height(glyphHeight)
                                    .aspectRatio(attribution.width / attribution.height),
                        )
                    }
                }
            }
        } else if (showPercent) {
            viewModel.batteryPercent?.let {
                BasicText(
                    text = it,
                    color = colorProducer,
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }

        if (showEstimate) {
            viewModel.batteryTimeRemainingEstimate?.let {
                BasicText(
                    text = it,
                    color = colorProducer,
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = 1),
                )
            }
        }
    }
}
