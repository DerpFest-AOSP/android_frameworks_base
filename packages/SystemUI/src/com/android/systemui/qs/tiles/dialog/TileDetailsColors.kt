/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.dialog

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.android.internal.R

/**
 * Returns the theme color that stays readable on [backgroundColor].
 *
 * Connected rows are filled with the dynamic primary, which can be a bright pastel. The matching
 * on-primary color is then too close to that fill for small text such as "5G SA".
 */
fun readableColorOn(context: Context, backgroundColor: Int): Int {
    val opaqueBackground = ColorUtils.setAlphaComponent(backgroundColor, 0xFF)
    val candidates =
        intArrayOf(
            context.getColor(R.color.materialColorOnPrimary),
            context.getColor(R.color.materialColorOnPrimaryContainer),
            context.getColor(R.color.materialColorOnSurface),
            context.getColor(R.color.materialColorOnPrimaryFixed),
            Color.BLACK,
            Color.WHITE,
        )
    return candidates.maxBy { color ->
        try {
            ColorUtils.calculateContrast(color, opaqueBackground)
        } catch (_: IllegalArgumentException) {
            0.0
        }
    }
}
