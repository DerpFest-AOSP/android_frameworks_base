/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import android.view.CrossWindowBlurListeners
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.ColorUtils
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.common.ui.view.BackgroundBlurAlphaSync
import com.android.systemui.res.R
import com.android.systemui.shared.system.BlurUtils
import java.util.function.Consumer

/**
 * True when surface blur should actually be drawn: the blur-on-surfaces flag is on, this device
 * can blur, and cross-window blur is not currently suppressed (battery saver, thermal, tunnel).
 */
@Composable
internal fun rememberSurfaceBlurActive(): Boolean {
    val eligible = BlurUtils.isVolumeAndPowerBlurEnabled() && BlurUtils.supportsBlursOnWindows()
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(
            eligible && CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled
        )
    }
    DisposableEffect(eligible) {
        if (!eligible) {
            enabled = false
            return@DisposableEffect onDispose {}
        }
        val listeners = CrossWindowBlurListeners.getInstance()
        val consumer = Consumer<Boolean> { enabled = it }
        listeners.addListener(context.mainExecutor, consumer)
        onDispose { listeners.removeListener(consumer) }
    }
    return eligible && enabled
}

/**
 * Frosted fill used by the volume panel and the media bottom sheet: [surfaceEffect0] over
 * [surfaceEffect2]. Falls back to an opaque surface when blur cannot run.
 */
@Composable
internal fun expressiveCardContainerColor(
    blurActive: Boolean = rememberSurfaceBlurActive(),
): Color {
    val scheme = LocalAndroidColorScheme.current
    return when {
        blurActive ->
            Color(
                ColorUtils.compositeColors(
                    scheme.surfaceEffect0.toArgb(),
                    scheme.surfaceEffect2.toArgb(),
                )
            )
        BlurUtils.isVolumeAndPowerBlurEnabled() -> scheme.surfaceEffect0Fallback
        else -> ExpressiveCardColor
    }
}

/**
 * Clips to [shape] and, when surface blur is active, blurs what is behind the window inside the
 * card. Otherwise paints [expressiveCardContainerColor] as a plain fill.
 */
@Composable
internal fun Modifier.surfaceBlur(shape: Shape, cornerRadius: Dp): Modifier {
    val active = rememberSurfaceBlurActive()
    val color = expressiveCardContainerColor(active)
    val drawable = rememberBackgroundBlurDrawable()
    val blurRadius = dimensionResource(R.dimen.volume_dialog_background_surface_blur_radius)
    val density = LocalDensity.current
    val clipped = clip(shape)
    SideEffect {
        if (!active) drawable?.setBlurRadius(0)
    }
    if (!active || drawable == null) {
        return clipped.background(color)
    }
    return clipped.drawBehind {
        drawable.setColor(color.toArgb())
        drawable.setBlurRadius(with(density) { blurRadius.roundToPx() })
        drawable.setCornerRadius(cornerRadius.toPx())
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { canvas -> drawable.draw(canvas.nativeCanvas) }
    }
}

@Composable
private fun rememberBackgroundBlurDrawable(): BackgroundBlurDrawable? {
    val view = LocalView.current
    var drawable by remember(view) {
        mutableStateOf(view.viewRootImpl?.createBackgroundBlurDrawable()?.apply { setBlurRadius(0) })
    }
    DisposableEffect(view) {
        val attachListener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (drawable == null) {
                        drawable =
                            v.viewRootImpl?.createBackgroundBlurDrawable()?.apply { setBlurRadius(0) }
                    }
                    v.removeOnAttachStateChangeListener(this)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            }
        if (!view.isAttachedToWindow) {
            view.addOnAttachStateChangeListener(attachListener)
        } else if (drawable == null) {
            drawable = view.viewRootImpl?.createBackgroundBlurDrawable()?.apply { setBlurRadius(0) }
        }
        val alphaSync =
            BackgroundBlurAlphaSync(view) { drawable }.start()
        onDispose {
            view.removeOnAttachStateChangeListener(attachListener)
            alphaSync.dispose()
            drawable?.setBlurRadius(0)
        }
    }
    return drawable
}
