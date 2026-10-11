/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade

import android.view.DisplayCutout
import android.view.Surface

/**
 * Horizontal camera-cutout insets for the landscape split shade.
 *
 * A portrait top camera sits on the left in [Surface.ROTATION_90] and on the right in
 * [Surface.ROTATION_270]. Inset sources sometimes keep that padding on one side for both
 * directions; this puts it back on the side the camera is actually on.
 */
object LandscapeSplitShadeCutout {
    /** Physical left and right insets, in pixels. */
    fun horizontalInsets(
        cutout: DisplayCutout?,
        rotation: Int,
        reverseDefaultRotation: Boolean = false,
    ): Pair<Int, Int> {
        if (cutout == null) {
            return 0 to 0
        }
        val left = cutout.safeInsetLeft
        val right = cutout.safeInsetRight
        val inset = maxOf(left, right)
        if (inset == 0) {
            return 0 to 0
        }
        // Reverse default rotation swaps landscape and seascape, so the camera side swaps too.
        val cameraOnLeftAt90 = !reverseDefaultRotation
        val expectLeft =
            when (rotation) {
                Surface.ROTATION_90 -> cameraOnLeftAt90
                Surface.ROTATION_270 -> !cameraOnLeftAt90
                else -> return left to right
            }
        return when {
            expectLeft && right > left -> inset to 0
            !expectLeft && left > right -> 0 to inset
            else -> left to right
        }
    }
}
