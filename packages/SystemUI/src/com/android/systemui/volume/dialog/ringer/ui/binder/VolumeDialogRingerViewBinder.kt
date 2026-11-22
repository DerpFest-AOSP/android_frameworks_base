/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog.ringer.ui.binder

import android.animation.ArgbEvaluator
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.gui.EarlyWakeupInfo
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.View
import android.widget.ImageButton
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.MotionScene
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.internal.R as internalR
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.qs.ui.compose.resolveVolumeSliderGradientArgb
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.util.VolumeDialogRingerDrawerTransitionListener
import com.android.systemui.volume.dialog.ringer.ui.util.updateCloseState
import com.android.systemui.volume.dialog.ringer.ui.util.updateOpenState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonUiModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerDrawerState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModelState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.VolumeDialogRingerDrawerViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.utils.setBlurFallbackColor
import com.android.systemui.volume.dialog.ui.utils.suspendAnimate
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import javax.inject.Inject
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.job

private const val CLOSE_DRAWER_DELAY = 300L
// Ensure roundness and color of button is updated when progress is changed by a minimum fraction.
private const val BUTTON_MIN_VISIBLE_CHANGE = 0.05F

@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogScope
class VolumeDialogRingerViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogRingerDrawerViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
) : ViewBinder {

    private val roundnessSpringForce =
        SpringForce(1F).apply {
            stiffness = 800F
            dampingRatio = 0.6F
        }
    private val colorSpringForce =
        SpringForce(1F).apply {
            stiffness = 3800F
            dampingRatio = 1F
        }
    private val rgbEvaluator = ArgbEvaluator()
    private val transaction = SurfaceControl.Transaction()
    private var isInEarlyWakeUp = false
    private val earlyWakeupInfo =
        EarlyWakeupInfo().apply {
            token = Binder()
            trace = TAG
        }

    private val onAttachStateChangeListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (windowRootViewBlurInteractor.isBlurCurrentlySupported.value) {
                    startEarlyWakeup()
                }
            }

            override fun onViewDetachedFromWindow(view: View) {
                if (windowRootViewBlurInteractor.isBlurCurrentlySupported.value) {
                    endEarlyWakeup()
                }
            }
        }

    override fun CoroutineScope.bind(view: View) {
        val volumeDialogBackgroundView = view.requireViewById<View>(R.id.volume_dialog_background)
        val ringerBackgroundView = view.requireViewById<View>(R.id.ringer_buttons_background)
        val drawerContainer = view.requireViewById<MotionLayout>(R.id.volume_ringer_drawer)

        val unselectedButtonUiModel = RingerButtonUiModel.getUnselectedButton(view.context)
        val selectedButtonUiModel = RingerButtonUiModel.getSelectedButton(view.context)
        val volumeDialogBgSmallRadius =
            view.context.resources.getDimensionPixelSize(
                R.dimen.volume_dialog_background_square_corner_radius
            )
        val volumeDialogBgFullRadius =
            view.context.resources.getDimensionPixelSize(
                R.dimen.volume_dialog_background_corner_radius
            )
        val bottomDefaultRadius = volumeDialogBgFullRadius.toFloat()
        val bottomCornerRadii =
            floatArrayOf(
                0F,
                0F,
                0F,
                0F,
                bottomDefaultRadius,
                bottomDefaultRadius,
                bottomDefaultRadius,
                bottomDefaultRadius,
            )
        var backgroundAnimationProgress: Float by
            Delegates.observable(0F) { _, _, progress ->
                ringerBackgroundView.applyCorners(
                    fullRadius = volumeDialogBgFullRadius,
                    diff = volumeDialogBgFullRadius - volumeDialogBgSmallRadius,
                    progress,
                )
            }
        val ringerDrawerTransitionListener = VolumeDialogRingerDrawerTransitionListener {
            backgroundAnimationProgress = it
        }
        drawerContainer.setTransitionListener(ringerDrawerTransitionListener)

        volumeDialogBackgroundView.updateBackground()
        ringerBackgroundView.updateBackground()
        launchTraced("VDRVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(ringerBackgroundView)
        }

        if (viewModel.showBlur) {
            launchTraced("VDRVB#isBlurCurrentlySupported") {
                windowRootViewBlurInteractor.isBlurCurrentlySupported.collect { supported ->
                    if (view.isAttachedToWindow) {
                        if (supported) {
                            startEarlyWakeup()
                        } else {
                            endEarlyWakeup()
                        }
                    }

                    volumeDialogBackgroundView.setIsBlurSupported(supported)
                    ringerBackgroundView.setIsBlurSupported(supported)
                }
            }

            view.addOnAttachStateChangeListener(onAttachStateChangeListener)
        }

        var gradientColorsForRinger: Pair<Int, Int>? = resolveVolumeSliderGradientArgb(view.context)
        var selectedRingerButtonRef: ImageButton? = null
        val contentResolver = view.context.contentResolver
        val gradientObserver =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    gradientColorsForRinger = resolveVolumeSliderGradientArgb(view.context)
                    if (!view.isAttachedToWindow) return
                    selectedRingerButtonRef?.applySelectedAppearance(gradientColorsForRinger)
                }
            }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.QS_VOLUME_GRADIENT_ENABLED),
            false,
            gradientObserver,
            UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.GRADIENT_START_COLOR),
            false,
            gradientObserver,
            UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.GRADIENT_END_COLOR),
            false,
            gradientObserver,
            UserHandle.USER_ALL,
        )
        coroutineContext.job.invokeOnCompletion {
            contentResolver.unregisterContentObserver(gradientObserver)
        }

        viewModel.ringerViewModel
            .mapLatest { ringerState ->
                when (ringerState) {
                    is RingerViewModelState.Available -> {
                        val uiModel = ringerState.uiModel
                        val orientation =
                            if (
                                view.context.resources.getBoolean(
                                    R.bool.volume_dialog_ringer_drawer_should_open_to_the_side
                                )
                            ) {
                                ringerState.orientation
                            } else {
                                Configuration.ORIENTATION_PORTRAIT
                            }

                        // Set up view background and visibility
                        drawerContainer.visibility = View.VISIBLE
                        if (viewModel.showBlur) {
                            val layers = (volumeDialogBackgroundView.background as LayerDrawable)
                            val blurDrawable = layers.getDrawable(0) as BackgroundBlurDrawable
                            blurDrawable.setCornerRadius(
                                0f,
                                0f,
                                bottomDefaultRadius,
                                bottomDefaultRadius,
                            )
                            (layers.getDrawable(1) as GradientDrawable).cornerRadii =
                                bottomCornerRadii
                        } else {
                            (volumeDialogBackgroundView.background as GradientDrawable)
                                .cornerRadii = bottomCornerRadii
                        }
                        when (uiModel.drawerState) {
                            is RingerDrawerState.Initial -> {
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                    gradientColorsForRinger,
                                    { selectedRingerButtonRef = it },
                                )
                                ringerDrawerTransitionListener.setProgressChangeEnabled(true)
                                drawerContainer.closeDrawer(
                                    ringerBackgroundView,
                                    uiModel.currentButtonIndex,
                                    orientation,
                                )
                            }
                            is RingerDrawerState.Closed -> {
                                if (
                                    uiModel.selectedButton.ringerMode ==
                                        uiModel.drawerState.currentMode
                                ) {
                                    drawerContainer.animateAndBindDrawerButtons(
                                        viewModel,
                                        uiModel,
                                        selectedButtonUiModel,
                                        unselectedButtonUiModel,
                                        gradientColorsForRinger,
                                        { selectedRingerButtonRef = it },
                                        onProgressChanged = { progress, isReverse ->
                                            // Let's make button progress when switching matches
                                            // motionLayout transition progress. When full
                                            // radius,
                                            // progress is 0.0. When small radius, progress is
                                            // 1.0.
                                            backgroundAnimationProgress =
                                                if (isReverse) {
                                                    1F - progress
                                                } else {
                                                    progress
                                                }
                                        },
                                    ) {
                                        if (
                                            uiModel.currentButtonIndex ==
                                                uiModel.availableButtons.size - 1
                                        ) {
                                            ringerDrawerTransitionListener.setProgressChangeEnabled(
                                                false
                                            )
                                        } else {
                                            ringerDrawerTransitionListener.setProgressChangeEnabled(
                                                true
                                            )
                                        }
                                        drawerContainer.closeDrawer(
                                            ringerBackgroundView,
                                            uiModel.currentButtonIndex,
                                            orientation,
                                        )
                                    }
                                }
                            }
                            is RingerDrawerState.Open -> {
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                    gradientColorsForRinger,
                                    { selectedRingerButtonRef = it },
                                )
                                // Open drawer
                                if (
                                    uiModel.currentButtonIndex == uiModel.availableButtons.size - 1
                                ) {
                                    ringerDrawerTransitionListener.setProgressChangeEnabled(false)
                                } else {
                                    ringerDrawerTransitionListener.setProgressChangeEnabled(true)
                                }
                                updateOpenState(drawerContainer, orientation, ringerBackgroundView)
                                drawerContainer
                                    .getTransition(R.id.close_to_open_transition)
                                    .setInterpolatorInfo(
                                        MotionScene.Transition.INTERPOLATE_REFERENCE_ID,
                                        null,
                                        R.anim.volume_dialog_ringer_open,
                                    )
                                drawerContainer.transitionToState(
                                    R.id.volume_dialog_ringer_drawer_open
                                )
                                ringerBackgroundView.background =
                                    ringerBackgroundView.background.mutate()
                            }
                        }
                    }
                    is RingerViewModelState.Unavailable -> {
                        drawerContainer.visibility = View.GONE
                        if (viewModel.showBlur) {
                            val layers = (volumeDialogBackgroundView.background as LayerDrawable)
                            val blurDrawable = layers.getDrawable(0) as BackgroundBlurDrawable
                            blurDrawable.setCornerRadius(volumeDialogBgFullRadius.toFloat())
                            (layers.getDrawable(1) as GradientDrawable).cornerRadius =
                                volumeDialogBgFullRadius.toFloat()
                        } else {
                            if (viewModel.isVolumeDialogVertical) {
                                volumeDialogBackgroundView.setBackgroundResource(
                                    R.drawable.volume_dialog_background
                                )
                            } else {
                                volumeDialogBackgroundView.setBackgroundResource(
                                    R.drawable.volume_dialog_background_horizontal
                                )
                            }
                        }
                    }
                }
            }
            .launchInTraced("VDRVB#ringerViewModel", this)
    }

    private fun View.setIsBlurSupported(supported: Boolean) {
        if (viewModel.showBlur) {
            val layers = (background as LayerDrawable)
            (layers.getDrawable(0) as BackgroundBlurDrawable).setBlurRadius(
                if (supported) {
                    context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_background_surface_blur_radius
                    )
                } else {
                    0
                }
            )
            (layers.getDrawable(1) as GradientDrawable).setBlurFallbackColor(context, supported)
        }
    }

    private fun startEarlyWakeup() {
        if (!isInEarlyWakeUp) {
            transaction.setEarlyWakeupStart(earlyWakeupInfo)
            transaction.apply()
            isInEarlyWakeUp = true
        }
    }

    private fun endEarlyWakeup() {
        if (isInEarlyWakeUp) {
            transaction.setEarlyWakeupEnd(earlyWakeupInfo)
            transaction.apply()
            isInEarlyWakeUp = false
        }
    }

    private suspend fun MotionLayout.animateAndBindDrawerButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        selectedButtonUiModel: RingerButtonUiModel,
        unselectedButtonUiModel: RingerButtonUiModel,
        gradientColorsForRinger: Pair<Int, Int>? = null,
        onSelectedButtonBound: (ImageButton?) -> Unit = {},
        onProgressChanged: (Float, Boolean) -> Unit = { _, _ -> },
        onAnimationEnd: Runnable? = null,
    ) {
        ensureChildCount(R.layout.volume_ringer_button, uiModel.availableButtons.size)
        if (
            uiModel.drawerState is RingerDrawerState.Closed &&
                uiModel.drawerState.currentMode != uiModel.drawerState.previousMode
        ) {
            val count = uiModel.availableButtons.size
            val selectedButton = getChildAt(count - uiModel.currentButtonIndex) as ImageButton
            val previousIndex =
                uiModel.availableButtons.indexOfFirst {
                    it.ringerMode == uiModel.drawerState.previousMode
                }
            // We only need to execute on roundness animation end and volume dialog background
            // progress update once because these changes should be applied once on volume dialog
            // background and ringer drawer views.
            coroutineScope {
                val selectedCornerRadius = selectedButton.backgroundShape()?.cornerRadius
                if (
                    selectedCornerRadius != null &&
                        selectedCornerRadius.toInt() != selectedButtonUiModel.cornerRadius
                ) {
                    launchTraced("VDRVB#selectedButtonAnimation") {
                        selectedButton.animateTo(
                            RingerButtonUiModel.getSelectedButton(
                                context,
                                gradientColorsForRinger,
                            ),
                            if (uiModel.currentButtonIndex == count - 1) {
                                onProgressChanged
                            } else {
                                { _, _ -> }
                            },
                        )
                    }
                }
                if (previousIndex >= 0) {
                    val unselectedButton = getChildAt(count - previousIndex) as ImageButton

                    val unselectedCornerRadius = unselectedButton.backgroundShape()?.cornerRadius
                    if (
                        unselectedCornerRadius != null &&
                            unselectedCornerRadius.toInt() != unselectedButtonUiModel.cornerRadius
                    ) {
                        launchTraced("VDRVB#unselectedButtonAnimation") {
                            unselectedButton.animateTo(
                                unselectedButtonUiModel,
                                if (previousIndex == count - 1) {
                                    onProgressChanged
                                } else {
                                    { _, _ -> }
                                },
                            )
                        }
                    }
                }
                launchTraced("VDRVB#bindButtons") {
                    delay(CLOSE_DRAWER_DELAY)
                    bindButtons(
                        viewModel,
                        uiModel,
                        onAnimationEnd,
                        gradientColorsForRinger,
                        onSelectedButtonBound,
                    )
                }
            }
        } else {
            bindButtons(
                viewModel,
                uiModel,
                onAnimationEnd,
                gradientColorsForRinger = gradientColorsForRinger,
                onSelectedButtonBound = onSelectedButtonBound,
            )
        }
    }

    private fun MotionLayout.bindButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        onAnimationEnd: Runnable? = null,
        gradientColorsForRinger: Pair<Int, Int>? = null,
        onSelectedButtonBound: (ImageButton?) -> Unit = {},
    ) {
        onSelectedButtonBound(null)
        val count = uiModel.availableButtons.size
        uiModel.availableButtons.fastForEachIndexed { index, ringerButton ->
            val view = getChildAt(count - index) as ImageButton
            val isOpen = uiModel.drawerState is RingerDrawerState.Open
            if (index == uiModel.currentButtonIndex) {
                view.bindDrawerButton(
                    if (isOpen) ringerButton else uiModel.selectedButton,
                    viewModel,
                    isOpen,
                    isSelected = true,
                    gradientColorsForRinger = gradientColorsForRinger,
                    onSelectedButtonBound = { onSelectedButtonBound(view) },
                )
            } else {
                view.bindDrawerButton(ringerButton, viewModel, isOpen)
            }
        }
        onAnimationEnd?.run()
    }

    private fun ImageButton.bindDrawerButton(
        buttonViewModel: RingerButtonViewModel,
        viewModel: VolumeDialogRingerDrawerViewModel,
        isOpen: Boolean,
        isSelected: Boolean = false,
        gradientColorsForRinger: Pair<Int, Int>? = null,
        onSelectedButtonBound: (() -> Unit)? = null,
    ) {
        // id = buttonViewModel.viewId
        setSelected(isSelected)
        val ringerContentDesc = context.getString(buttonViewModel.contentDescriptionResId)
        setImageResource(buttonViewModel.imageResId)
        contentDescription =
            if (isSelected && !isOpen) {
                context.getString(
                    R.string.volume_ringer_drawer_closed_content_description,
                    ringerContentDesc,
                )
            } else {
                ringerContentDesc
            }
        if (isSelected) {
            onSelectedButtonBound?.invoke()
            applySelectedAppearance(gradientColorsForRinger)
        } else {
            applyUnselectedAppearance()
        }
        setOnClickListener {
            viewModel.onRingerButtonClicked(buttonViewModel.ringerMode, isSelected)
        }
    }

    private fun MotionLayout.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
        val childCountDelta = childCount - count - 1
        when {
            childCountDelta > 0 -> {
                removeViews(0, childCountDelta)
            }
            childCountDelta < 0 -> {
                val inflater = LayoutInflater.from(context)
                repeat(-childCountDelta) {
                    inflater.inflate(viewLayoutId, this, true)
                    getChildAt(childCount - 1).id = View.generateViewId()
                }
            }
        }
    }

    private fun MotionLayout.closeDrawer(
        ringerBackground: View,
        selectedIndex: Int,
        orientation: Int,
    ) {
        setTransition(R.id.close_to_open_transition)
        getTransition(R.id.close_to_open_transition)
            .setInterpolatorInfo(
                MotionScene.Transition.INTERPOLATE_REFERENCE_ID,
                null,
                R.anim.volume_dialog_ringer_close,
            )
        updateCloseState(this, selectedIndex, orientation, ringerBackground)
        transitionToState(R.id.volume_dialog_ringer_drawer_close)
    }

    private suspend fun ImageButton.animateTo(
        ringerButtonUiModel: RingerButtonUiModel,
        onProgressChanged: (Float, Boolean) -> Unit = { _, _ -> },
    ) {
        background = background.mutate()
        val shape = backgroundShape() ?: return
        val startIconColor = currentIconColor() ?: ringerButtonUiModel.tintColor
        val startColors = shape.gradientOrSolidColors()
        val targetStart =
            ringerButtonUiModel.gradientStartColor ?: ringerButtonUiModel.backgroundColor
        val targetEnd = ringerButtonUiModel.backgroundColor
        val roundnessAnimation =
            SpringAnimation(FloatValueHolder(0F), 1F).setSpring(roundnessSpringForce)
        val colorAnimation = SpringAnimation(FloatValueHolder(0F), 1F).setSpring(colorSpringForce)
        val radius = shape.cornerRadius
        val cornerRadiusDiff = ringerButtonUiModel.cornerRadius - radius

        roundnessAnimation.minimumVisibleChange = BUTTON_MIN_VISIBLE_CHANGE
        colorAnimation.minimumVisibleChange = BUTTON_MIN_VISIBLE_CHANGE
        coroutineScope {
            launchTraced("VDRVB#colorAnimation") {
                colorAnimation.suspendAnimate { value ->
                    val fraction = value.coerceIn(0F, 1F)
                    val currentIconColor =
                        rgbEvaluator.evaluate(
                            fraction,
                            startIconColor,
                            ringerButtonUiModel.tintColor,
                        ) as Int
                    val currentStart =
                        rgbEvaluator.evaluate(fraction, startColors.first, targetStart) as Int
                    val currentEnd =
                        rgbEvaluator.evaluate(fraction, startColors.second, targetEnd) as Int
                    shape.setFillColors(currentStart, currentEnd)
                    background.invalidateSelf()
                    setIconTint(currentIconColor)
                }
            }
            roundnessAnimation.suspendAnimate { value ->
                onProgressChanged(value, cornerRadiusDiff > 0F)
                shape.cornerRadius = radius + value * cornerRadiusDiff
                background.invalidateSelf()
            }
        }
    }

    private fun View.applyCorners(fullRadius: Int, diff: Int, progress: Float) {
        val radius = fullRadius - progress * diff
        if (viewModel.showBlur) {
            val layers = (background as LayerDrawable)
            (layers.getDrawable(0) as BackgroundBlurDrawable).setCornerRadius(radius)
            (layers.getDrawable(1) as GradientDrawable).cornerRadius = radius
        } else {
            (background as GradientDrawable).cornerRadius = radius
        }
        background.invalidateSelf()
    }

    private fun View.updateBackground() {
        if (viewModel.showBlur && background is GradientDrawable) {
            val surfaceEffect = background as GradientDrawable

            val blurDrawable = viewRootImpl.createBackgroundBlurDrawable()
            val dialogCornerRadius: Int =
                context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_background_corner_radius
                )
            blurDrawable.setCornerRadius(dialogCornerRadius.toFloat())
            blurDrawable.setBlurRadius(0)
            setBackgroundDrawable(LayerDrawable(arrayOf<Drawable>(blurDrawable, surfaceEffect)))

            setIsBlurSupported(windowRootViewBlurInteractor.isBlurCurrentlySupported.value)
        } else {
            background = background.mutate()
        }
    }

    private fun ImageButton.applySelectedAppearance(gradientColors: Pair<Int, Int>?) {
        if (gradientColors != null) {
            if (!hasGradientSelectionBackground(gradientColors)) {
                applyGradientSelectionBackground(gradientColors)
            }
            setIconTint(BatteryColors.textColorOnBackground(context, gradientColors.second))
        } else {
            setBackgroundResource(R.drawable.volume_drawer_selection_bg)
            background = background.mutate()
            setIconTint(context.getColor(internalR.color.materialColorOnPrimary))
        }
    }

    private fun ImageButton.applyUnselectedAppearance() {
        setBackgroundResource(R.drawable.volume_ringer_item_bg)
        background = background.mutate()
        setIconTint(context.getColor(internalR.color.materialColorOnSurface))
    }

    private fun ImageButton.hasGradientSelectionBackground(gradientColors: Pair<Int, Int>): Boolean {
        if (background !is InsetDrawable) return false
        val colors = backgroundShape()?.colors ?: return false
        return colors.size >= 2 &&
            colors.first() == gradientColors.first &&
            colors.last() == gradientColors.second
    }

    /** Sets the selected ringer button background to a gradient, matching the volume slider. */
    private fun ImageButton.applyGradientSelectionBackground(gradientColors: Pair<Int, Int>) {
        val (start, end) = gradientColors
        val radiusPx =
            resources
                .getDimensionPixelSize(R.dimen.volume_dialog_ringer_selected_button_background_radius)
                .toFloat()
        val gradientDrawable =
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(start, end)).apply {
                setCornerRadius(radiusPx)
            }
        val insetPx = (4 * resources.displayMetrics.density).toInt()
        background = InsetDrawable(gradientDrawable, insetPx, insetPx, insetPx, insetPx)
    }

    private fun ImageButton.setIconTint(color: Int) {
        clearColorFilter()
        imageTintList = ColorStateList.valueOf(color)
    }

    private fun ImageButton.currentIconColor(): Int? {
        return imageTintList?.defaultColor
    }

    companion object {
        private const val TAG = "VolumeDialogRingerViewBinder"
    }
}

private fun ImageButton.backgroundShape(): GradientDrawable? {
    return when (val bg = background) {
        is InsetDrawable -> bg.drawable as? GradientDrawable
        is GradientDrawable -> bg
        else -> null
    }
}

/** Solid fill, or the first and last stops when the button is using a gradient. */
private fun GradientDrawable.gradientOrSolidColors(): Pair<Int, Int> {
    color?.defaultColor?.let {
        return it to it
    }
    val colors = colors
    if (colors != null && colors.isNotEmpty()) {
        return colors.first() to colors.last()
    }
    return 0 to 0
}

private fun GradientDrawable.setFillColors(startColor: Int, endColor: Int) {
    if (startColor == endColor) {
        setColor(startColor)
    } else {
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(startColor, endColor)
    }
}
