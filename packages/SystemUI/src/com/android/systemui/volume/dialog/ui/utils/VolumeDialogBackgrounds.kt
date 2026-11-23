/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.dialog.ui.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.android.systemui.res.R
import com.android.systemui.shade.ui.ShadeColors
import kotlin.math.roundToInt

/**
 * Colors the surface layer of a volume dialog background for whether blur is [supported]. Without
 * blur the pitch black theme draws it black with a thin outline, so it keeps an edge over the
 * wallpaper.
 */
fun GradientDrawable.setBlurFallbackColor(context: Context, supported: Boolean) {
    if (!supported && ShadeColors.isBlackTheme(context)) {
        setColor(Color.BLACK)
        setStroke(
            context.resources.displayMetrics.density.roundToInt().coerceAtLeast(1),
            context.getColor(com.android.internal.R.color.materialColorSurfaceContainerHighest),
        )
        return
    }
    setStroke(0, Color.TRANSPARENT)
    setColor(
        context.getColor(
            if (supported) {
                R.color.volume_dialog_view_background_blur
            } else {
                R.color.volume_dialog_view_background_blur_fallback
            }
        )
    )
}

/**
 * Paints a volume surface black when the pitch black theme is on and this view is not drawing
 * blur. Leaves every other background alone.
 */
fun View.applyPitchBlackBackground() {
    val drawable = background as? GradientDrawable ?: return
    if (!ShadeColors.isBlackTheme(context)) {
        return
    }
    val mutated = drawable.mutate() as GradientDrawable
    mutated.setBlurFallbackColor(context, supported = false)
    background = mutated
}
