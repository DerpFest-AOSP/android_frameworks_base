/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2026 DerpFest AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.pulse

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var renderer: PulseRenderer? = null
    private var engine: PulseEngine? = null
    private var isAttached = false
    private var settingsRepo: PulseSettingsRepository? = null

    private var fadeAnimator: ValueAnimator? = null
    private val fadeInterpolator = DecelerateInterpolator()

    /**
     * Bars keep drawing for the whole fade, including fade-out. Cleared only once alpha hits 0
     * so a hide does not skip straight to an empty view.
     */
    private var rendering = false

    val isRendering: Boolean
        get() = rendering

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        alpha = 0f
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun initialize(settingsRepo: PulseSettingsRepository) {
        this.settingsRepo = settingsRepo

        renderer = PulseRenderer(context, settingsRepo)
        engine = PulseEngine(context, settingsRepo) { processedHeights ->
            if (!rendering) return@PulseEngine
            renderer?.updateHeights(processedHeights)
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached = false
        engine?.stop()
        renderer?.cleanup()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isAttached && rendering && alpha > 0f) {
            renderer?.onDraw(canvas, width, height)
            postInvalidateOnAnimation()
        }
    }

    fun updateVisualizerData(data: PulseData) {
        if (isAttached && rendering && data.isDataValid) {
            engine?.processFFT(data.fftBytes!!)
        }
    }

    fun onMediaColorsChanged(color: Int) {
        post { renderer?.onMediaColorsChanged(color) }
    }

    /**
     * Shows or hides the visualizer. A hide keeps drawing until the fade finishes; [onHidden]
     * runs only after that, and never if a later show cancels the fade.
     */
    fun setShown(shown: Boolean, animated: Boolean = true, onHidden: (() -> Unit)? = null) {
        cancelFade()

        if (shown) {
            rendering = true
            visibility = VISIBLE
            fadeTo(1f, FADE_IN_DURATION_MS, animated, onEnd = null)
            return
        }

        if (!rendering && visibility == GONE && alpha == 0f) {
            onHidden?.invoke()
            return
        }

        fadeTo(0f, FADE_OUT_DURATION_MS, animated) {
            rendering = false
            alpha = 0f
            visibility = GONE
            onHidden?.invoke()
        }
    }

    private fun fadeTo(
        target: Float,
        fullDurationMs: Long,
        animated: Boolean,
        onEnd: (() -> Unit)?
    ) {
        val distance = abs(target - alpha)
        if (!animated || distance < 0.001f) {
            alpha = target
            onEnd?.invoke()
            return
        }

        val animator = ValueAnimator.ofFloat(alpha, target)
        animator.duration = (fullDurationMs * distance).toLong().coerceAtLeast(1L)
        animator.interpolator = fadeInterpolator
        animator.addUpdateListener { animation ->
            alpha = animation.animatedValue as Float
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled || fadeAnimator !== animator) return
                fadeAnimator = null
                alpha = target
                onEnd?.invoke()
            }
        })
        fadeAnimator = animator
        animator.start()
    }

    private fun cancelFade() {
        val running = fadeAnimator ?: return
        fadeAnimator = null
        running.cancel()
    }

    companion object {
        private const val FADE_IN_DURATION_MS = 300L
        private const val FADE_OUT_DURATION_MS = 250L
    }
}
