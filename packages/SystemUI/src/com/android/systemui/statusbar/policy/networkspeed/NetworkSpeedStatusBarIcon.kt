/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.policy.networkspeed

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.domain.interactor.IsAreaDark
import com.android.systemui.statusbar.policy.NetworkSpeedController

/** Compose status bar slot for [NetworkSpeedView] when system icons use the compose pipeline. */
@Composable
fun NetworkSpeedStatusBarIcon(
    controller: NetworkSpeedController,
    isDark: IsAreaDark,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val slot = context.getString(R.string.status_bar_network_speed)
    val iconState by controller.iconState.collectAsState()
    var bounds by remember { mutableStateOf(Rect()) }
    val tint = if (isDark.isDarkTheme(bounds)) Color.White else Color.Black

    if (iconState?.isVisible() != true) {
        return
    }

    AndroidView(
        factory = { ctx -> NetworkSpeedView.fromContext(ctx, slot, blocked = false, controller) },
        update = { view ->
            view.applyNetworkState(iconState)
            view.setStaticDrawableColor(tint.toArgb())
        },
        modifier =
            modifier.onLayoutRectChanged { relativeLayoutBounds ->
                bounds =
                    with(relativeLayoutBounds.boundsInScreen) { Rect(left, top, right, bottom) }
            },
    )
}
