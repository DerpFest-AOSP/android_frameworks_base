/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.scene.ui.viewmodel

import android.text.TextUtils
import android.view.View
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.SwipeSource
import com.android.compose.animation.scene.SwipeSourceDetector
import com.android.systemui.shade.shared.model.QuickPulldown
import java.util.Locale

/**
 * A [SwipeSourceDetector] that resolves the outer quarter of the top edge on the [quickPulldown]
 * side to [SceneContainerArea.Resolved.QuickPulldown], and defers to [edgeDetector] elsewhere.
 */
class QuickPulldownSwipeDetector(
    private val edgeDetector: SwipeSourceDetector,
    private val quickPulldown: QuickPulldown,
) : SwipeSourceDetector {

    override fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SwipeSource.Resolved? {
        val source = edgeDetector.source(layoutSize, position, density, orientation)
        if (source != Edge.Resolved.Top) {
            return source
        }

        val isRtl =
            TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) ==
                View.LAYOUT_DIRECTION_RTL
        val onRight = (quickPulldown == QuickPulldown.END) != isRtl
        val region = layoutSize.width / 4f
        val inRegion =
            if (onRight) position.x > layoutSize.width - region else position.x < region
        return if (inRegion) SceneContainerArea.Resolved.QuickPulldown else source
    }
}
