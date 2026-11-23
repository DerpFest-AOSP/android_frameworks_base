/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.scene.ui.viewmodel

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.SwipeSource
import com.android.compose.animation.scene.SwipeSourceDetector
import com.android.systemui.shade.shared.model.QuickPulldown

/**
 * A [SwipeSourceDetector] that resolves the outer quarter of the top edge on the [quickPulldown]
 * side to [SceneContainerArea.Resolved.QuickPulldown], and defers to [edgeDetector] elsewhere.
 *
 * [isRtl] is the shade layout direction, read when the gesture starts.
 */
class QuickPulldownSwipeDetector(
    private val edgeDetector: SwipeSourceDetector,
    private val quickPulldown: QuickPulldown,
    private val isRtl: () -> Boolean,
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

        val onRight = (quickPulldown == QuickPulldown.END) != isRtl()
        val region = layoutSize.width / 4f
        val inRegion =
            if (onRight) position.x > layoutSize.width - region else position.x < region
        return if (inRegion) SceneContainerArea.Resolved.QuickPulldown else source
    }
}
