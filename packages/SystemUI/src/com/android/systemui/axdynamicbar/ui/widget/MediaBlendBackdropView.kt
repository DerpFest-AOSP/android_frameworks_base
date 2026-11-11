/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.widget

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageSwitcher
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.android.systemui.res.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accord/Cupertino [BlendView]-style layered album backdrop: saturated art, blurred stack, two slow
 * rotating corner crops plus full-bleed, and a dark veil on top ([R.color.ax_blend_front_shade]).
 * Built for embedding behind the Dynamic Bar expanded media card (narrow height); omits Accord’s
 * fullscreen blur-edge scale hack. After the veil, draws a small aspect-fit curve
 * veil ([R.drawable.ax_blend_curve_veil]) with [BlendMode.SOFT_LIGHT] (Accord-style plastic shimmer).
 * The view itself is an opaque dark floor ([R.color.ax_blend_card_base]) so empty ImageSwitchers
 * or a failed art bind never punch a hole through a translucent overlay.
 */
class MediaBlendBackdropView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private val imageCornerTl: ImageSwitcher
    private val imageCornerBr: ImageSwitcher
    private val imageBg: ImageSwitcher
    private val rotateFrame: ConstraintLayout
    private val blurredFrame: ConstraintLayout
    private val overlayColorInt = ContextCompat.getColor(context, R.color.ax_blend_front_shade)
    private var previousBitmapOriginal: Bitmap? = null

    private val supervisor = SupervisorJob()
    private val workloadScope = CoroutineScope(supervisor + Dispatchers.Main.immediate)
    private var enhanceJob: Job? = null
    /** Skip redundant pipeline when Compose re-invokes AndroidView(update) every frame */
    private var boundAlbumArt: Drawable? = null
    private var animateArtChange = true

    private var choreographerRunning = false
    private var lastFrameTimeNanos = 0L
    private val frameIntervalNanos = 1_000_000_000L / 30

    companion object {
        /** Stronger wash than before; pairs with a smaller decoded bitmap for a softer mesh. */
        private const val BLUR_RADIUS_PX = 128f

        /** Accord matches [uk.akane.cupertino.widget.special.BlendView.SATURATION_FACTOR] */
        private const val SATURATION_FACTOR = 2f
        private const val ROTATION_CYCLE = 360
        private const val IMAGE_TRANSITION_MS = 400L
        /** Tighter cap so CenterCrop + blur reads as color haze, not sharp linework. */
        private const val MAX_ALBUM_BITMAP_SIDE_PX = 512

        /** Raster size for [R.drawable.ax_blend_curve_veil] (kept moderate for one-time allocation). */
        private const val CURVE_VEIL_RASTER_PX = 384

        /**
         * Accord uses ~30 + animation; static layer alpha for the gloss pass (SOFT_LIGHT blends down
         * visually).
         */
        private const val CURVE_VEIL_LAYER_ALPHA = 42
        private val decodeLock = Any()
    }

    private var curveVeilBitmap: Bitmap? = null

    private val curveOverlayPaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
            alpha = CURVE_VEIL_LAYER_ALPHA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blendMode = BlendMode.SOFT_LIGHT
            }
        }

    init {
        inflate(context, R.layout.ax_media_blend_backdrop, this)

        imageCornerTl = findViewById(R.id.ax_blend_corner_tl)
        imageCornerBr = findViewById(R.id.ax_blend_corner_br)
        imageBg = findViewById(R.id.ax_blend_bg)
        rotateFrame = findViewById(R.id.ax_blend_rotate_frame)
        blurredFrame = findViewById(R.id.ax_blend_blurred_views)

        clipChildren = true
        clipToPadding = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(ContextCompat.getColor(context, R.color.ax_blend_card_base))

        initSwitchers(imageCornerTl, imageCornerBr, imageBg)
        installBlurEffect()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureCurveVeilBitmap()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    private fun ensureCurveVeilBitmap() {
        if (curveVeilBitmap != null) return
        val drawable =
            ContextCompat.getDrawable(context, R.drawable.ax_blend_curve_veil)?.mutate()
                ?: return
        curveVeilBitmap =
            drawable.toBitmap(CURVE_VEIL_RASTER_PX, CURVE_VEIL_RASTER_PX, Bitmap.Config.ARGB_8888)
    }

    private fun recycleCurveVeilBitmap() {
        curveVeilBitmap?.recycle()
        curveVeilBitmap = null
    }

    private fun drawCurveVeilIfReady(canvas: Canvas) {
        val bmp = curveVeilBitmap ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth < 1f || viewHeight < 1f) return

        val bmpWidth = bmp.width.toFloat()
        val bmpHeight = bmp.height.toFloat()
        val scale = min(viewWidth / bmpWidth, viewHeight / bmpHeight)
        val scaledWidth = bmpWidth * scale
        val scaledHeight = bmpHeight * scale
        val left = (viewWidth - scaledWidth) / 2f
        val top = (viewHeight - scaledHeight) / 2f
        val dstRect = RectF(left, top, left + scaledWidth, top + scaledHeight)
        canvas.drawBitmap(bmp, null, dstRect, curveOverlayPaint)
    }

    private fun initSwitchers(vararg switchers: ImageSwitcher) {
        val animIn =
            AnimationUtils.loadAnimation(context, android.R.anim.fade_in).apply {
                duration = IMAGE_TRANSITION_MS
            }
        val animOut =
            AnimationUtils.loadAnimation(context, android.R.anim.fade_out).apply {
                duration = IMAGE_TRANSITION_MS
            }
        for (switcher in switchers) {
            switcher.setFactory {
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )


                    setLayerType(LAYER_TYPE_SOFTWARE, null)
                }
            }
            switcher.inAnimation = animIn
            switcher.outAnimation = animOut
        }
    }

    private fun installBlurEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurredFrame.setRenderEffect(
                RenderEffect.createBlurEffect(
                    BLUR_RADIUS_PX,
                    BLUR_RADIUS_PX,
                    Shader.TileMode.MIRROR,
                ),
            )
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        canvas.drawColor(overlayColorInt)
        drawCurveVeilIfReady(canvas)
    }

    /**
     * Binds session album art ([Drawable]) for saturation + blurred mesh. Repeated calls with the same
     * drawable instance are ignored until [releaseBackdrop].
     */
    fun bindAlbumArt(drawable: Drawable?, animate: Boolean = true) {
        if (drawable == null) {
            boundAlbumArt = null
            clearBackdropInternal(stopRotation = true)
            return
        }
        if (drawable === boundAlbumArt && previousBitmapOriginal != null) return
        boundAlbumArt = drawable
        animateArtChange = animate

        enhanceJob?.cancel()
        enhanceJob =
            workloadScope.launch {
                val extracted =
                    withContext(Dispatchers.Default) {
                        synchronized(decodeLock) {
                            drawableToBitmapSafe(drawable, MAX_ALBUM_BITMAP_SIDE_PX)
                        }
                    } ?: run {
                        clearBackdropInternal(stopRotation = true)
                        return@launch
                    }

                if (!bitmapRoughlyMatchesPrevious(extracted, previousBitmapOriginal)) {
                    previousBitmapOriginal = extracted
                    val enhanced = enhanceBitmapSaturation(extracted)
                    withContext(Dispatchers.Main.immediate) {
                        if (!isAttachedToWindow) return@withContext
                        populateLayers(enhanced)
                        startRotationLoop()
                    }
                }
            }
    }

    private fun drawableToBitmapSafe(d: Drawable, maxSidePx: Int): Bitmap? {
        return runCatching {
            val iw = max(1, d.intrinsicWidth)
            val ih = max(1, d.intrinsicHeight)
            val scale =
                max(iw, ih).takeIf { it > maxSidePx }?.let { maxSidePx / it.toFloat() } ?: 1f
            val tw = max(1, ceil(iw * scale.toDouble()).toInt())
            val th = max(1, ceil(ih * scale.toDouble()).toInt())
            d.toBitmap(tw, th)
        }.getOrNull()
    }

    private fun bitmapRoughlyMatchesPrevious(candidate: Bitmap, previous: Bitmap?): Boolean {
        if (previous == null) return false
        if (previous === candidate) return true
        if (candidate.width != previous.width || candidate.height != previous.height) return false
        val c1 = candidate.config
        val c2 = previous.config
        if (c1 == Bitmap.Config.HARDWARE || c2 == Bitmap.Config.HARDWARE) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            candidate.sameAs(previous)
        } else {
            false
        }
    }

    private fun populateLayers(enhancedBitmap: Bitmap) {
        val switchers = arrayOf(imageCornerTl, imageCornerBr, imageBg)
        val animations =
            if (animateArtChange) {
                null
            } else {
                switchers.map { it.inAnimation to it.outAnimation }
            }
        if (animations != null) {
            switchers.forEach { switcher ->
                switcher.inAnimation = null
                switcher.outAnimation = null
            }
        }
        imageCornerTl.setImageDrawable(cropTopLeftQuarter(enhancedBitmap).toDrawable(resources))
        imageCornerBr.setImageDrawable(cropBottomRightQuarter(enhancedBitmap).toDrawable(resources))
        imageBg.setImageDrawable(enhancedBitmap.toDrawable(resources))
        animations?.forEachIndexed { index, (fadeIn, fadeOut) ->
            switchers[index].inAnimation = fadeIn
            switchers[index].outAnimation = fadeOut
        }
    }

    private fun clearBackdropInternal(stopRotation: Boolean) {
        enhanceJob?.cancel()
        enhanceJob = null
        previousBitmapOriginal = null
        imageCornerTl.setImageDrawable(null)
        imageCornerBr.setImageDrawable(null)
        imageBg.setImageDrawable(null)
        imageCornerTl.rotation = 0f
        imageCornerBr.rotation = 0f
        rotateFrame.rotation = 0f
        if (stopRotation) {
            stopRotationLoop()
        }
    }

    /** Called from Compose AndroidView(onRelease={ … }). */
    fun releaseBackdrop() {
        boundAlbumArt = null
        clearBackdropInternal(stopRotation = true)
        supervisor.cancelChildren()
    }

    private fun enhanceBitmapSaturation(bitmap: Bitmap): Bitmap {
        val safeBitmap =
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            } else {
                bitmap
            }

        val w = safeBitmap.width
        val h = safeBitmap.height
        val enhancedBitmap = createBitmap(w, h)
        enhancedBitmap.density = safeBitmap.density

        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter =
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION_FACTOR) })
        }
        Canvas(enhancedBitmap).drawBitmap(safeBitmap, 0f, 0f, paint)

        return enhancedBitmap
    }

    private fun cropTopLeftQuarter(bitmap: Bitmap): Bitmap {
        val quarterWidth = bitmap.width / 2
        val quarterHeight = bitmap.height / 2
        return Bitmap.createBitmap(bitmap, 0, 0, quarterWidth, quarterHeight)
    }

    private fun cropBottomRightQuarter(bitmap: Bitmap): Bitmap {
        val quarterWidth = bitmap.width / 2
        val quarterHeight = bitmap.height / 2
        return Bitmap.createBitmap(bitmap, quarterWidth, quarterHeight, quarterWidth, quarterHeight)
    }

    private fun startRotationLoop() {
        if (choreographerRunning || alpha == 0f || !isAttachedToWindow) return
        choreographerRunning = true
        lastFrameTimeNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopRotationLoop() {
        if (!choreographerRunning) return
        choreographerRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        stopRotationLoop()
        enhanceJob?.cancel()
        supervisor.cancelChildren()
        recycleCurveVeilBitmap()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!choreographerRunning || !isAttachedToWindow) {
            Choreographer.getInstance().removeFrameCallback(this)
            return
        }

        if (frameTimeNanos - lastFrameTimeNanos >= frameIntervalNanos) {
            lastFrameTimeNanos = frameTimeNanos

            imageCornerTl.rotation = (imageCornerTl.rotation + 0.3f) % ROTATION_CYCLE
            imageCornerBr.rotation = (imageCornerBr.rotation + 0.2f) % ROTATION_CYCLE
            rotateFrame.rotation = (rotateFrame.rotation - 0.1f + ROTATION_CYCLE) % ROTATION_CYCLE
            invalidate()
        }
        if (choreographerRunning && isAttachedToWindow) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}
