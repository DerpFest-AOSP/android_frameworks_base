/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.ui.compose

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors

data class QsGradientColors(
    val start: Color,
    val end: Color,
) {
    /** Midpoint stop, used for contrast on centered tile icons. */
    val mid: Color
        get() = qsGradientMidColor(start, end)
}

/** 50% blend of [start] and [end], used for contrast on centered icons. */
fun qsGradientMidColor(start: Color, end: Color): Color {
    return Color(ColorUtils.blendARGB(start.toArgb(), end.toArgb(), 0.5f))
}

/**
 * Horizontal (or vertical) brush from [start] to [end]. Combined vs separate QS panels have
 * different tile/slider aspect ratios; a corner-to-corner [Brush.linearGradient] follows those
 * bounds and makes the same custom colors look swapped or muddy after a shade-mode switch.
 */
fun qsGradientBrush(
    start: Color,
    end: Color,
    isVertical: Boolean = false,
): Brush {
    val startOpaque = start.copy(alpha = 1f)
    val endOpaque = end.copy(alpha = 1f)
    val colors =
        if (startOpaque == endOpaque) {
            listOf(startOpaque.lighten(0.2f), startOpaque, startOpaque.darken(0.2f))
        } else {
            listOf(startOpaque, endOpaque)
        }
    return if (isVertical) {
        Brush.verticalGradient(colors)
    } else {
        Brush.horizontalGradient(colors)
    }
}

/** Readable icon/label color on top of a QS gradient stop. */
@Composable
fun rememberContrastColorOn(background: Color): Color {
    val context = LocalContext.current
    return remember(background, context) {
        Color(BatteryColors.textColorOnBackground(context, background.copy(alpha = 1f).toArgb()))
    }
}

/**
 * Resolved gradient start/end for tiles and sliders. Uses [Settings.System.GRADIENT_START_COLOR] /
 * [Settings.System.GRADIENT_END_COLOR] when set (non-zero ARGB); otherwise the theme defaults.
 */
@Composable
fun rememberQsGradientColors(): QsGradientColors {
    val (customStart, customEnd) = rememberQsGradientCustomColors()
    val context = LocalContext.current
    val resources = LocalResources.current
    val isDark = isSystemInDarkTheme()
    val assetsSeq = LocalConfiguration.current.assetsSeq
    val defaultStart =
        remember(isDark, resources, context.theme, assetsSeq) {
            val id =
                if (isDark) {
                    R.color.derpfestui_color_gradient_start_dark
                } else {
                    R.color.derpfestui_color_gradient_start_light
                }
            Color(resources.getColor(id, context.theme))
        }
    val defaultEnd =
        remember(isDark, resources, context.theme, assetsSeq) {
            val id =
                if (isDark) {
                    R.color.derpfestui_color_gradient_end_dark
                } else {
                    R.color.derpfestui_color_gradient_end_light
                }
            Color(resources.getColor(id, context.theme))
        }
    return QsGradientColors(
        start = customStart ?: defaultStart,
        end = customEnd ?: defaultEnd,
    )
}

/** User-chosen gradient start/end. Null means fall back to the theme default. */
@Composable
fun rememberQsGradientCustomColors(): Pair<Color?, Color?> {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    fun readStart(): Int {
        return try {
            Settings.System.getIntForUser(
                contentResolver,
                Settings.System.GRADIENT_START_COLOR,
                0,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Throwable) {
            0
        }
    }

    fun readEnd(): Int {
        return try {
            Settings.System.getIntForUser(
                contentResolver,
                Settings.System.GRADIENT_END_COLOR,
                0,
                UserHandle.USER_CURRENT,
            )
        } catch (_: Throwable) {
            0
        }
    }

    var startArgb by remember(contentResolver) { mutableIntStateOf(readStart()) }
    var endArgb by remember(contentResolver) { mutableIntStateOf(readEnd()) }

    DisposableEffect(contentResolver) {
        startArgb = readStart()
        endArgb = readEnd()
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    context.mainExecutor.execute {
                        startArgb = readStart()
                        endArgb = readEnd()
                    }
                }
            }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.GRADIENT_START_COLOR),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.GRADIENT_END_COLOR),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    return Pair(
        if (startArgb == 0) null else gradientSettingArgbToColor(startArgb),
        if (endArgb == 0) null else gradientSettingArgbToColor(endArgb),
    )
}

/**
 * View-side volume gradient colors. Null when [Settings.System.QS_VOLUME_GRADIENT_ENABLED] is off.
 */
fun resolveVolumeSliderGradientArgb(context: Context): Pair<Int, Int>? {
    return try {
        if (
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.QS_VOLUME_GRADIENT_ENABLED,
                1,
                UserHandle.USER_CURRENT,
            ) != 1
        ) {
            return null
        }
        val isDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val startArgb =
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.GRADIENT_START_COLOR,
                0,
                UserHandle.USER_CURRENT,
            )
        val endArgb =
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.GRADIENT_END_COLOR,
                0,
                UserHandle.USER_CURRENT,
            )
        val start =
            if (startArgb != 0) {
                forceOpaqueArgb(startArgb)
            } else {
                context.getColor(
                    if (isDark) {
                        R.color.derpfestui_color_gradient_start_dark
                    } else {
                        R.color.derpfestui_color_gradient_start_light
                    }
                )
            }
        val end =
            if (endArgb != 0) {
                forceOpaqueArgb(endArgb)
            } else {
                context.getColor(
                    if (isDark) {
                        R.color.derpfestui_color_gradient_end_dark
                    } else {
                        R.color.derpfestui_color_gradient_end_light
                    }
                )
            }
        Pair(start, end)
    } catch (_: Throwable) {
        null
    }
}

/** Treats 0x00RRGGBB as opaque so color-picker values without alpha still paint fully. */
private fun gradientSettingArgbToColor(argb: Int): Color {
    val a = (argb shr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val alpha = if (a == 0) 1f else a / 255f
    return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = alpha)
}

private fun forceOpaqueArgb(argb: Int): Int {
    val a = (argb shr 24) and 0xFF
    return if (a == 0) argb or 0xFF000000.toInt() else argb
}

private fun Color.lighten(amount: Float): Color = blendWith(Color.White, amount)

private fun Color.darken(amount: Float): Color = blendWith(Color.Black, amount)

private fun Color.blendWith(other: Color, ratio: Float): Color {
    return Color(ColorUtils.blendARGB(this.toArgb(), other.toArgb(), ratio))
}
