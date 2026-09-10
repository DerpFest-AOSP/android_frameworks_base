/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.battery.shared.ui

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors.LightTheme.Charging
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors.LightTheme.Error
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors.LightTheme.PowerSave

sealed interface BatteryColors {
    /** Foreground color for battery glyphs (e.g. percentage or charging bolt) */
    val glyph: Color
    /** Foreground color for the filled portion representing the level */
    val fill: Color
    /**
     * Background color for when there are no glyphs. Should provide better contrast to the [fill]
     * color to improve the ability to glance at the level
     *
     * Note that the [Charging] and [PowerSave] states will always have a foreground glyph, so they
     * will not use this color.
     */
    val backgroundOnly: Color

    /** Background color suitable for providing contrast with the [glyph] color */
    val backgroundWithGlyph: Color

    /** The layered attribution. Should match the tint of other status bar icons */
    val attribution: Color

    /**
     * Light theme: light background, dark icons
     *
     * For color profiles that are non-default ([Charging], [Error], [PowerSave]), the foreground
     * [glyph] color is darker. This means that we want to use the low-alpha background definition
     * for the backgrounds. Using the low alpha variant will mix with a lighter background more and
     * thus allow for higher contrast with the darker [glyph] colors.
     */
    sealed class LightTheme : BatteryColors {
        override val attribution = Color.Black
        override val glyph = Color.Black.copy(alpha = 0.75f)
        override val backgroundOnly = lowAlphaBg
        override val backgroundWithGlyph = lowAlphaBg

        data object Default : LightTheme() {
            override val glyph = Color.White.copy(alpha = 0.9f)
            override val fill = Color.Black

            /** Use a higher opacity here because the foreground is white */
            override val backgroundWithGlyph = highAlphaBg
        }

        data object Charging : LightTheme() {
            override val fill = Color(0xFF18CC47)
        }

        data object Error : LightTheme() {
            override val fill = Color(0xFFFF0E01)
        }

        data object PowerSave : LightTheme() {
            override val fill = Color(0xFFFFC917)
        }

        companion object {
            private val lowAlphaBg = Color.Black.copy(alpha = 0.20f)
            private val highAlphaBg = Color.Black.copy(alpha = 0.55f)
        }
    }

    /**
     * Dark theme: dark background, light icons
     *
     * Similar to the light theme, the non-default ([Charging], [Error], [PowerSave]) colors use a
     * darker [glyph] color. But since these icons will be drawn onto darker backgrounds, we use the
     * opposite approach for the background, choosing the higher alpha variants to maximize
     * contrast.
     */
    sealed class DarkTheme : BatteryColors {
        override val attribution = Color.White
        override val backgroundOnly = lowAlphaBg
        override val backgroundWithGlyph = highAlphaBg
        override val glyph = Color.Black.copy(alpha = 0.75f)

        data object Default : DarkTheme() {
            override val fill = Color.White
        }

        data object Charging : DarkTheme() {
            override val fill = Color(0xFF18CC47)
        }

        data object Error : DarkTheme() {
            override val fill = Color(0xFFFF0E01)
        }

        data object PowerSave : DarkTheme() {
            override val fill = Color(0xFFFFC917)
        }

        companion object {
            private val lowAlphaBg = Color.White.copy(alpha = 0.45f)
            private val highAlphaBg = Color.White.copy(alpha = 0.55f)
        }
    }

    /** Accent color theme for light mode */
    class AccentLightTheme(
        private val accentColor: Color,
        useHighEnd: Boolean
    ) : LightTheme() {
        override val attribution = accentColor
        override val glyph = darkerAccentShade(accentColor, useHighEnd)
        override val fill = accentColor
        override val backgroundOnly = accentColor.copy(alpha = 0.20f)
        override val backgroundWithGlyph = accentColor.copy(alpha = 0.70f)
    }

    /** Accent color theme for dark mode */
    class AccentDarkTheme(
        private val accentColor: Color,
        useHighEnd: Boolean
    ) : DarkTheme() {
        override val attribution = accentColor
        override val glyph = darkerAccentShade(accentColor, useHighEnd)
        override val fill = accentColor
        override val backgroundOnly = accentColor.copy(alpha = 0.45f)
        override val backgroundWithGlyph = accentColor.copy(alpha = 0.70f)
    }

    /**
     * Copies [base] but paints fill and attribution with [tint], so the battery matches
     * neighboring status icons (e.g. on a shade highlight chip).
     */
    class MatchedIconTint(base: BatteryColors, tint: Color) : BatteryColors {
        override val glyph = base.glyph
        override val fill = tint
        override val backgroundOnly = tint.copy(alpha = base.backgroundOnly.alpha)
        override val backgroundWithGlyph = tint.copy(alpha = base.backgroundWithGlyph.alpha)
        override val attribution = tint
    }

    companion object {
        /**
         * Calculates a readable text color (ARGB) to sit on top of the given background color.
         * Uses luminance to pick black or white, then blends until WCAG contrast is met (or 80%
         * blend when highPrecision is false).
         *
         * @param backgroundArgb The background color (e.g. accent or chip fill).
         * @param highPrecision If true, uses an iterative loop for WCAG 4.5:1. If false, 80% blend.
         * @return ARGB int suitable for [android.graphics.Paint.setColor] / [android.widget.TextView.setTextColor].
         */
        fun textColorOnBackground(context: Context, backgroundArgb: Int): Int {
            val useHighEnd = context.resources.getBoolean(R.bool.config_useHighEndBatteryContrast)
            return textColorOnBackgroundArgb(backgroundArgb, useHighEnd)
        }

        /**
         * Core luminance-aware contrast logic. Returns ARGB int.
         */
        private fun textColorOnBackgroundArgb(backgroundArgb: Int, highPrecision: Boolean): Int {
            val bgLum = ColorUtils.calculateLuminance(backgroundArgb)
            val isBgLight = bgLum > 0.5
            val targetColor = if (isBgLight) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            val modeStr = if (highPrecision) "High-End Loop" else "Low-End Approx"

            if (!highPrecision) {
                Log.d("StatusBarTint:", "Calculating tint ($modeStr) for bg=${Integer.toHexString(backgroundArgb)}")
                return ColorUtils.blendARGB(backgroundArgb, targetColor, 0.8f)
            }

            val minContrast = 6.5
            var blendRatio = 0.0f
            if (ColorUtils.calculateContrast(backgroundArgb, backgroundArgb) >= minContrast) {
                return backgroundArgb
            }

            Log.d("StatusBarTint:", "Calculating tint ($modeStr) for bg=${Integer.toHexString(backgroundArgb)}")
            while (blendRatio <= 1.0f) {
                val newColorArgb = ColorUtils.blendARGB(backgroundArgb, targetColor, blendRatio)
                if (ColorUtils.calculateContrast(newColorArgb, backgroundArgb) >= minContrast) {
                    Log.d("StatusBarTint:", "Found contrast match at ratio $blendRatio: ${Integer.toHexString(newColorArgb)}")
                    return newColorArgb
                }
                blendRatio += 0.05f
            }
            return targetColor
        }

        /**
         * Calculates a readable text color to sit on top of the accent fill (Compose Color).
         *
         * @param accent The background fill color (Monet accent).
         * @param highPrecision If true, uses an iterative loop to find the perfect tint.
         * If false, uses a fast approximation (80% blend) to save CPU.
         */
        private fun darkerAccentShade(accent: Color, highPrecision: Boolean): Color {
            return Color(textColorOnBackgroundArgb(accent.toArgb(), highPrecision))
        }

        /**
         * Create accent color themes from Android color int.
         * Requires Context to check device config.
         */
        fun createAccentThemes(context: Context, accentColorInt: Int): Pair<LightTheme, DarkTheme> {
            val accentColor = Color(accentColorInt)

            // Use the correct SystemUI resource class
            val useHighEnd = context.resources.getBoolean(R.bool.config_useHighEndBatteryContrast)

            return Pair(
                AccentLightTheme(accentColor, useHighEnd),
                AccentDarkTheme(accentColor, useHighEnd)
            )
        }
    }
}
