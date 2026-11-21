/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.shared.model

/** The status bar edge that opens Quick Settings directly, matching qs_quick_pulldown values. */
enum class QuickPulldown {
    OFF,
    /** Right edge, or left edge in RTL layouts. */
    END,
    /** Left edge, or right edge in RTL layouts. */
    START;

    companion object {
        fun fromInt(value: Int): QuickPulldown =
            when (value) {
                1 -> END
                2 -> START
                else -> OFF
            }
    }
}
