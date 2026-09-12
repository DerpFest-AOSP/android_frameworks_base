/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.WindowConfiguration
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.settingslib.Utils
import com.android.systemui.clock.ClockModernization
import com.android.systemui.derpfest.logo.LogoImage
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingIn
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingOut
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.RunningChipAnim
import com.android.systemui.statusbar.phone.LyricViewController
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.policy.ConfigurationController
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lineageos.providers.LineageSettings

/**
 * Interface to assist with binding the [CollapsedStatusBarFragment] to [HomeStatusBarViewModel].
 * Used only to enable easy testing of [CollapsedStatusBarFragment].
 */
interface HomeStatusBarViewBinder {
    /**
     * Binds the view to the view-model. [listener] will be notified whenever an event that may
     * change the status bar visibility occurs.
     *
     * If non-null, chip animations control the animation of the system icon area to support the
     * chip animations.
     */
    fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        listener: StatusBarVisibilityChangeListener?,
    )
}

@PerDisplaySingleton
class HomeStatusBarViewBinderImpl
@Inject
constructor(
    private val darkIconDispatcher: DarkIconDispatcher,
    private val configurationController: ConfigurationController,
) : HomeStatusBarViewBinder {
    private data class ClockState(
        val autoHide: Boolean,
        val denyListed: Boolean,
        val hideForHun: Boolean,
        val chipStyle: Int,
        val dynamicIslandEnabled: Boolean,
        val position: Int,
        val visibilityModel: VisibilityModel,
    )

    private data class Padding(
        val start: Int, val top: Int, val end: Int, val bottom: Int,
    )

    override fun bind(
        displayId: Int,
        view: View,
        viewModel: HomeStatusBarViewModel,
        systemEventChipAnimateIn: ((View) -> Unit)?,
        systemEventChipAnimateOut: ((View) -> Unit)?,
        listener: StatusBarVisibilityChangeListener?,
    ) {
        // Set some top-level views to gone before we get started
        val systemInfoView = view.requireViewById<View>(R.id.status_bar_end_side_content)
        val leftClock: Clock = view.requireViewById(R.id.clock)
        val centerClock: Clock? = view.findViewById(R.id.clock_center)
        val rightClock: Clock? = view.findViewById(R.id.clock_right)
        val notificationIconsArea = view.requireViewById<View>(R.id.notification_icon_area)
        val leftLogo: LogoImage = view.requireViewById(R.id.statusbar_logo)

        val leftPaddingInit = leftClock.capturePadding()
        val centerPaddingInit = centerClock?.capturePadding()
        val rightPaddingInit = rightClock?.capturePadding()

        // GONE because this shouldn't take space in the layout
        systemInfoView.hideInitially()
        leftClock.hideInitially(state = View.GONE)
        centerClock?.hideInitially(state = View.GONE)
        rightClock?.hideInitially(state = View.GONE)
        leftLogo.hideInitially()
        notificationIconsArea.hideInitially()

        view.repeatWhenAttached {
            val lyricController = LyricController(view).also { it.hideInitially() }
            try {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                val context = view.context

                val clockState =
                    MutableStateFlow(
                        ClockState(
                            autoHide = false,
                            denyListed = false,
                            hideForHun = false,
                            chipStyle = 0,
                            dynamicIslandEnabled = context.contentResolver.readDynamicIslandEnabled(),
                            position = context.contentResolver.readClockPosition(),
                            visibilityModel = VisibilityModel(View.GONE, true),
                        )
                    )

                val wallpaperRefreshEpoch = MutableStateFlow(0)
                val wallpaperChipLoadJob = AtomicReference<Job?>(null)
                val appContext = context.applicationContext
                val wallpaperChangedReceiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, intent: Intent?) {
                            wallpaperRefreshEpoch.update { it + 1 }
                        }
                    }
                appContext.registerReceiver(
                    wallpaperChangedReceiver,
                    IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED,
                )

                val clockAutoHideUri: Uri =
                    LineageSettings.System.getUriFor(
                        LineageSettings.System.STATUS_BAR_CLOCK_AUTO_HIDE
                    )
                val iconHideListUri: Uri =
                    Settings.Secure.getUriFor(StatusBarIconController.ICON_HIDE_LIST)
                val statusBarClockUri: Uri =
                    LineageSettings.System.getUriFor(LineageSettings.System.STATUS_BAR_CLOCK)
                val statusBarClockChipUri: Uri =
                    Settings.System.getUriFor(Settings.System.STATUSBAR_CLOCK_CHIP)
                val dynamicIslandUri: Uri =
                    Settings.System.getUriFor(Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND)

                val taskStackListener =
                    object : TaskStackChangeListener {
                        override fun onTaskStackChanged() {
                            val autoHide = shouldClockAutoHideForCurrentTask()

                            if (clockState.value.autoHide != autoHide) {
                                clockState.update { it.copy(autoHide = autoHide) }
                            }
                        }
                    }

                val contentObserver =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean, uri: Uri?) {
                            clockState.update { current ->
                                when (uri) {
                                    clockAutoHideUri -> {
                                        val enabled =
                                            context.contentResolver.readClockAutoHide() != 0

                                        if (enabled) {
                                            TaskStackChangeListeners.getInstance()
                                                .registerTaskStackListener(taskStackListener)
                                        } else {
                                            TaskStackChangeListeners.getInstance()
                                                .unregisterTaskStackListener(taskStackListener)
                                        }

                                        current.copy(
                                            autoHide =
                                                enabled && shouldClockAutoHideForCurrentTask()
                                        )
                                    }
                                    iconHideListUri ->
                                        current.copy(
                                            denyListed =
                                                StatusBarIconController.getIconHideList(
                                                        context,
                                                        Settings.Secure.getString(
                                                            context.contentResolver,
                                                            StatusBarIconController.ICON_HIDE_LIST,
                                                        ),
                                                    )
                                                    .contains("clock")
                                        )
                                    statusBarClockUri ->
                                        current.copy(
                                            position = context.contentResolver.readClockPosition()
                                        )
                                    statusBarClockChipUri -> {
                                        val chipStyle =
                                            Settings.System.getIntForUser(context.contentResolver,
                                                Settings.System.STATUSBAR_CLOCK_CHIP, 0,
                                                UserHandle.USER_CURRENT
                                            )
                                        current.copy(
                                            chipStyle = chipStyle
                                        )
                                    }
                                    dynamicIslandUri ->
                                        current.copy(
                                            dynamicIslandEnabled =
                                                context.contentResolver.readDynamicIslandEnabled()
                                        )
                                    else -> current
                                }
                            }
                        }
                    }

                val urisToObserve = listOf(clockAutoHideUri, iconHideListUri, statusBarClockUri, statusBarClockChipUri, dynamicIslandUri)
                urisToObserve.forEach { uri ->
                    context.contentResolver.registerContentObserver(
                        uri,
                        false,
                        contentObserver,
                        UserHandle.USER_ALL,
                    )
                    contentObserver.onChange(false, uri)
                }

                // Ensure cleanup when lifecycle ends
                val job = coroutineContext[Job]
                job?.invokeOnCompletion {
                    runCatching {
                        context.contentResolver.unregisterContentObserver(contentObserver)
                        TaskStackChangeListeners.getInstance()
                            .unregisterTaskStackListener(taskStackListener)
                        runCatching {
                            appContext.unregisterReceiver(wallpaperChangedReceiver)
                        }
                        wallpaperChipLoadJob.get()?.cancel()
                    }
                }
                listener?.let { listener ->
                    launch {
                        viewModel.isTransitioningFromLockscreenToOccluded.collect {
                            listener.onStatusBarVisibilityMaybeChanged()
                        }
                    }
                }

                listener?.let { listener ->
                    launch {
                        viewModel.transitionFromLockscreenToDreamStartedEvent.collect {
                            listener.onTransitionFromLockscreenToDreamStarted()
                        }
                    }
                }

                val lightsOutView: View = view.requireViewById(R.id.notification_lights_out)
                launch {
                    viewModel.areNotificationsLightsOut.collect { show ->
                        animateLightsOutView(lightsOutView, show)
                    }
                }

                if (com.android.media.projection.flags.Flags.showStopDialogPostCallEnd()) {
                    launch {
                        viewModel.mediaProjectionStopDialogDueToCallEndedState.collect { stopDialog
                            ->
                            if (stopDialog is MediaProjectionStopDialogModel.Shown) {
                                stopDialog.createAndShowDialog()
                            }
                        }
                    }
                }

                if (SceneContainerFlag.isEnabled) {
                    listener?.let { listener ->
                        launch {
                            viewModel.isHomeStatusBarAllowed.collect {
                                listener.onIsHomeStatusBarAllowedBySceneChanged(it)
                            }
                        }
                    }
                }

                // TODO(b/393445203): figure out the best story for this stub view. This crashes
                // if we move it up to the top of [bind]
                val operatorNameView = view.requireViewById<View>(R.id.operator_name_frame)
                operatorNameView.isVisible = false

                StatusBarOperatorNameViewBinder.bind(
                    operatorNameView,
                    viewModel.operatorNameViewModel,
                    viewModel.areaTint,
                )
                launch {
                    viewModel.shouldShowOperatorNameView.collect { operatorNameView.isVisible = it }
                }

                if (!ClockModernization.isEnabled) {
                    val chipAppearanceGeneration = MutableStateFlow(0)
                    val configListener =
                        object : ConfigurationController.ConfigurationListener {
                            override fun onThemeChanged() {
                                chipAppearanceGeneration.update { it + 1 }
                            }

                            override fun onUiModeChanged() {
                                chipAppearanceGeneration.update { it + 1 }
                            }

                            override fun onDensityOrFontScaleChanged() {
                                chipAppearanceGeneration.update { it + 1 }
                            }
                        }
                    configurationController.addCallback(configListener)
                    coroutineContext[Job]?.invokeOnCompletion {
                        configurationController.removeCallback(configListener)
                    }

                    launch {
                        combine(
                                viewModel.isClockVisible,
                                viewModel.hideStartSideContentForHeadsUp,
                            ) { visibilityModel, hideForHun ->
                                visibilityModel to hideForHun
                            }
                            .collect { (visibilityModel, hideForHun) ->
                                clockState.update { current ->
                                    current.copy(
                                        visibilityModel = visibilityModel,
                                        hideForHun = hideForHun,
                                    )
                                }
                            }
                    }

                    launch {
                        var lastChipStyle: Int? = null
                        var lastClockPosition: Int? = null
                        var lastChipAppearanceGeneration = 0
                        var lastWallpaperEpoch = -1

                        combine(
                                clockState,
                                chipAppearanceGeneration,
                                wallpaperRefreshEpoch,
                            ) { state, generation, wpEpoch ->
                                Triple(state, generation, wpEpoch)
                            }
                            .collect { (state, generation, wpEpoch) ->
                            // We only want to hide left clock for HUN
                            val hunBlocksClock =
                                state.position == CLOCK_POSITION_LEFT && state.hideForHun

                            // Apply denylist on top of ViewModel visibility
                            val finalVisibility =
                                if (
                                    state.visibilityModel.visibility == View.VISIBLE &&
                                        !hunBlocksClock &&
                                        !state.autoHide &&
                                        !state.denyListed &&
                                        !(state.position == CLOCK_POSITION_CENTER &&
                                            state.dynamicIslandEnabled)
                                ) {
                                    state.visibilityModel
                                } else {
                                    state.visibilityModel.copy(visibility = View.GONE)
                                }

                            // Pick active clock view
                            val activeClock: Clock? =
                                when (state.position) {
                                    CLOCK_POSITION_CENTER -> centerClock ?: leftClock
                                    CLOCK_POSITION_RIGHT -> rightClock ?: leftClock
                                    CLOCK_POSITION_LEFT -> leftClock
                                    else -> leftClock
                                }

                            // Hide all clocks first
                            leftClock.visibility = View.GONE
                            centerClock?.visibility = View.GONE
                            rightClock?.visibility = View.GONE

                            // Show only the active one
                            activeClock?.adjustVisibility(finalVisibility)

                            // Only touch chip UI when style, position, theme, density, or
                            // wallpaper (style 13) changes
                            val chipNeedsUpdate =
                                lastChipStyle != state.chipStyle ||
                                    lastClockPosition != state.position ||
                                    lastChipAppearanceGeneration != generation ||
                                    (state.chipStyle == CHIP_STYLE_WALLPAPER_THUMBNAIL &&
                                        lastWallpaperEpoch != wpEpoch)
                            if (chipNeedsUpdate) {
                                applyClockChip(
                                    coroutineScope = this,
                                    wallpaperChipLoadJob = wallpaperChipLoadJob,
                                    context = context,
                                    chipStyle = state.chipStyle,
                                    activeClock = activeClock,
                                    leftClock = leftClock,
                                    centerClock = centerClock,
                                    rightClock = rightClock,
                                    leftPaddingInit = leftPaddingInit,
                                    centerPaddingInit = centerPaddingInit,
                                    rightPaddingInit = rightPaddingInit
                                )
                                lastChipStyle = state.chipStyle
                                lastClockPosition = state.position
                                lastChipAppearanceGeneration = generation
                                lastWallpaperEpoch = wpEpoch
                            }
                        }
                    }
                }

                launch {
                    viewModel.isNotificationIconContainerVisible.collect {
                        lyricController.updateNotificationIconsVisibility(it)
                    }
                }

                launch {
                    combine(
                            viewModel.isNotificationIconContainerVisible,
                            viewModel.hideStartSideContentForHeadsUp,
                        ) { visibilityModel, hideForHun ->
                            visibilityModel to hideForHun
                        }
                        .collect { (visibilityModel, hideForHun) ->
                            leftLogo.setHiddenForHeadsUp(hideForHun)
                            val logoVisibility =
                                if (leftLogo.shouldShowLogo()) {
                                    visibilityModel
                                } else {
                                    visibilityModel.copy(visibility = View.GONE)
                                }
                            leftLogo.adjustVisibility(logoVisibility)
                        }
                }

                launch {
                    viewModel.isLyricEnabled.collect { lyricController.isEnabled = it }
                }

                launch {
                    viewModel.isLyricClockRightMode.collect {
                        lyricController.setLyricPosition(
                            if (it) LyricViewController.LYRIC_POSITION_CLOCK_RIGHT
                            else LyricViewController.LYRIC_POSITION_OVERLAY
                        )
                    }
                }

                launch {
                    viewModel.isLyricClockRightHideIcon.collect {
                        lyricController.setHideIconOnClockRight(it)
                    }
                }

                launch {
                    viewModel.isLyricTranslationEnabled.collect {
                        lyricController.setShowTranslation(it)
                    }
                }

                launch {
                    viewModel.isLyricVisible.collect { lyricController.adjustVisibility(it) }
                }

                launch {
                    viewModel.systemInfoCombinedVis.collect { (baseVis, animState) ->
                        // Broadly speaking, the baseVis controls the view.visibility, and
                        // the animation state uses only alpha to achieve its effect. This
                        // means that we can always modify the visibility, and if we're
                        // animating we can use the animState to handle it. If we are not
                        // animating, then we can use the baseVis default animation
                        if (animState.isAnimatingChip()) {
                            // Just apply the visibility of the view, but don't animate
                            systemInfoView.visibility = baseVis.visibility
                            // Now apply the animation state, with its animator
                            when (animState) {
                                AnimatingIn -> {
                                    systemEventChipAnimateIn?.invoke(systemInfoView)
                                }
                                AnimatingOut -> {
                                    systemEventChipAnimateOut?.invoke(systemInfoView)
                                }
                                else -> {
                                    // Nothing to do here
                                }
                            }
                        } else {
                            systemInfoView.adjustVisibility(baseVis)
                        }
                    }
                }
                }
            } finally {
                lyricController.releaseViews()
                lyricController.destroy()
            }
        }
    }

    private fun ContentResolver.readClockAutoHide(): Int {
        return LineageSettings.System.getIntForUser(
            this,
            LineageSettings.System.STATUS_BAR_CLOCK_AUTO_HIDE,
            0,
            UserHandle.USER_CURRENT,
        )
    }

    private fun ContentResolver.readClockPosition(): Int {
        return LineageSettings.System.getIntForUser(
            this,
            LineageSettings.System.STATUS_BAR_CLOCK,
            CLOCK_POSITION_LEFT,
            UserHandle.USER_CURRENT,
        )
    }

    private fun ContentResolver.readDynamicIslandEnabled(): Boolean {
        return Settings.System.getIntForUser(
            this,
            Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    private fun shouldClockAutoHideForCurrentTask(): Boolean {
        return ActivityManagerWrapper.getInstance()
            .runningTask
            ?.configuration
            ?.windowConfiguration
            ?.activityType == WindowConfiguration.ACTIVITY_TYPE_HOME
    }

    private fun SystemEventAnimationState.isAnimatingChip() =
        when (this) {
            AnimatingIn,
            AnimatingOut,
            RunningChipAnim -> true
            else -> false
        }

    private fun animateLightsOutView(view: View, visible: Boolean) {
        view.animate().cancel()

        val alpha = if (visible) 1f else 0f
        val duration = if (visible) 750L else 250L
        val visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }

        view
            .animate()
            .alpha(alpha)
            .setDuration(duration)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.alpha = alpha
                        view.visibility = visibility
                        // Unset the listener, otherwise this may persist for
                        // another view property animation
                        view.animate().setListener(null)
                    }
                }
            )
            .start()
    }

    private fun View.adjustVisibility(model: VisibilityModel) {
        if (model.visibility == View.VISIBLE) {
            this.show(model.shouldAnimateChange)
        } else {
            this.hide(model.visibility, model.shouldAnimateChange)
        }
    }

    private fun View.capturePadding() = Padding(paddingStart, paddingTop, paddingEnd, paddingBottom)

    /**
     * Same rounded-rect silhouette as [R.drawable.sb_date_bg] (uses [R.dimen.chip_corner_radius]).
     * A full stadium (`min(w,h)/2`) becomes a **circle** when the view is square — e.g. square
     * wallpaper thumbnails — so we cap corners to match the XML chips.
     */
    private fun applyCapsuleOutlineToClockChip(clock: Clock) {
        val cornerPx = clock.context.resources.getDimension(R.dimen.chip_corner_radius)
        clock.clipToOutline = true
        clock.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = view.width
                    val h = view.height
                    if (w <= 0 || h <= 0) {
                        outline.setEmpty()
                        return
                    }
                    val maxR = minOf(w, h) / 2f
                    val r = minOf(cornerPx, maxR)
                    outline.setRoundRect(0, 0, w, h, r)
                }
            }
        clock.invalidateOutline()
        clock.post { clock.invalidateOutline() }
    }

    private fun clearCapsuleOutlineFromClockChip(clock: Clock) {
        clock.clipToOutline = false
        clock.outlineProvider = null
        clock.invalidateOutline()
    }

    private fun applyClockChip(
        coroutineScope: CoroutineScope,
        wallpaperChipLoadJob: AtomicReference<Job?>,
        context: Context,
        chipStyle: Int,
        activeClock: Clock?,
        leftClock: Clock,
        centerClock: Clock?,
        rightClock: Clock?,
        leftPaddingInit: Padding,
        centerPaddingInit: Padding?,
        rightPaddingInit: Padding?
    ) {
        wallpaperChipLoadJob.get()?.cancel()
        wallpaperChipLoadJob.set(null)

        fun reset(clock: Clock?, padding: Padding?) {
            if (clock == null || padding == null) return
            clearCapsuleOutlineFromClockChip(clock)
            clock.setBackgroundResource(0)
            clock.setPaddingRelative(padding.start, padding.top, padding.end, padding.bottom)
            clock.setChipTextColorOverride(null)
            clock.setStaticColor(false)
            clock.setShouldApplyPadding(true)
            // Reset text color - Clock will handle it via DarkIconDispatcher
        }

        // Chip styles that are outline-only (transparent fill). Use normal icon color
        // so DarkIconDispatcher can adapt to light/dark status bar; filled chips use
        // luminance-aware contrast against colorAccent, same as the battery icon glyph.
        val outlineChipStyles = setOf(2, 8)

        fun apply(clock: Clock, style: Int) {
            val clockBackgrounds = listOf(
                R.drawable.sb_date_bg1,
                R.drawable.sb_date_bg2,
                R.drawable.sb_date_bg3,
                R.drawable.sb_date_bg4,
                R.drawable.sb_date_bg5,
                R.drawable.sb_date_bg6,
                R.drawable.sb_date_bg7,
                R.drawable.sb_date_bg8,
                R.drawable.sb_date_bg9,
                R.drawable.sb_date_bg10,
                R.drawable.sb_date_bg11,
                R.drawable.sb_date_bg12
            )

            if (style < 1 || style > clockBackgrounds.size) {
                clock.setChipTextColorOverride(null)
                clock.setStaticColor(false)
                clock.setShouldApplyPadding(true)
                return
            }

            clock.setShouldApplyPadding(false)

            val chipTopBottomPadding = context.resources.getDimensionPixelSize(
                R.dimen.status_bar_clock_chip_tb_padding)
            val chipLeftRightPadding = context.resources.getDimensionPixelSize(
                R.dimen.status_bar_clock_chip_lr_padding)

            clock.setBackgroundResource(clockBackgrounds[style - 1])
            clock.setPaddingRelative(
                chipLeftRightPadding,
                chipTopBottomPadding,
                chipLeftRightPadding,
                chipTopBottomPadding
            )
            clock.setTextAlignment(View.TEXT_ALIGNMENT_CENTER)
            if (style !in outlineChipStyles) {
                clock.setStaticColor(true)
                val chipBgColor = chipContrastBackground(context, style)
                val chipTextColor = BatteryColors.textColorOnBackground(context, chipBgColor)
                clock.setTextColor(chipTextColor)
                clock.setChipTextColorOverride(chipTextColor)
            } else {
                clock.setChipTextColorOverride(null)
                clock.setStaticColor(false)
                // Outline styles: no override; request current tint from DarkIconDispatcher now
                // so the clock gets the correct color without needing lock/unlock.
                darkIconDispatcher.applyDark(clock)
            }
            applyCapsuleOutlineToClockChip(clock)
        }

        // Always reset first so the previous active clock loses chip when position changes
        reset(leftClock, leftPaddingInit)
        reset(centerClock, centerPaddingInit)
        reset(rightClock, rightPaddingInit)

        if (chipStyle == 0) return

        if (chipStyle == CHIP_STYLE_WALLPAPER_THUMBNAIL) {
            val clock = activeClock ?: return
            clock.setShouldApplyPadding(false)
            val chipTopBottomPadding =
                context.resources.getDimensionPixelSize(R.dimen.status_bar_clock_chip_tb_padding)
            val chipLeftRightPadding =
                context.resources.getDimensionPixelSize(R.dimen.status_bar_clock_chip_lr_padding)
            clock.setPaddingRelative(
                chipLeftRightPadding,
                chipTopBottomPadding,
                chipLeftRightPadding,
                chipTopBottomPadding,
            )
            clock.setTextAlignment(View.TEXT_ALIGNMENT_CENTER)
            // Placeholder until IO load completes; chip outline from [applyCapsuleOutlineToClockChip].
            clock.background = ColorDrawable(Color.BLACK)
            clock.setStaticColor(true)
            val placeholderTextColor =
                BatteryColors.textColorOnBackground(context, Color.BLACK)
            clock.setTextColor(placeholderTextColor)
            clock.setChipTextColorOverride(placeholderTextColor)
            applyCapsuleOutlineToClockChip(clock)

            val job =
                coroutineScope.launch {
                    val chip =
                        withContext(Dispatchers.IO) {
                            ClockChipWallpaperThumbnailHelper.loadChipBackground(context)
                        }
                    ensureActive()
                    clock.background = chip.drawable
                    val textColor =
                        BatteryColors.textColorOnBackground(context, chip.contrastSampleArgb)
                    clock.setTextColor(textColor)
                    clock.setChipTextColorOverride(textColor)
                    applyCapsuleOutlineToClockChip(clock)
                }
            wallpaperChipLoadJob.set(job)
            return
        }

        activeClock?.let { apply(it, chipStyle) }
    }

    /**
     * Background color used for chip text contrast. Must match the battery icon, which tints glyphs
     * against [Utils.getColorAccentDefaultColor] (light accent in dark theme → dark text).
     *
     * Style 5 sits on neumorph paper rather than accent; style 9 has a full-size scrim.
     */
    private fun chipContrastBackground(context: Context, style: Int): Int {
        val accent = Utils.getColorAccentDefaultColor(context)
        return when (style) {
            5 ->
                ColorUtils.blendARGB(
                    context.getColor(R.color.neumorph_outline_start),
                    context.getColor(R.color.neumorph_outline_end),
                    0.5f,
                )
            9 -> ColorUtils.compositeColors(context.getColor(R.color.clock_chip_overlay), accent)
            else -> accent
        }
    }

    /**
     * Hide the view for initialization, but skip if it's already hidden and does not cancel
     * animations.
     */
    private fun View.hideInitially(state: Int = View.INVISIBLE) {
        if (visibility == View.INVISIBLE || visibility == View.GONE) {
            return
        }
        alpha = 0f
        visibility = state
    }

    // See CollapsedStatusBarFragment#hide.
    private fun View.hide(state: Int = View.INVISIBLE, shouldAnimateChange: Boolean) {
        animate().cancel()

        if (
            (visibility == View.INVISIBLE && state == View.INVISIBLE) ||
                (visibility == View.GONE && state == View.GONE)
        ) {
            return
        }
        val isAlreadyHidden = visibility == View.INVISIBLE || visibility == View.GONE
        if (!shouldAnimateChange || isAlreadyHidden) {
            alpha = 0f
            visibility = state
            return
        }

        animate()
            .alpha(0f)
            .setDuration(FADE_OUT_DURATION.toLong())
            .setStartDelay(0)
            .setInterpolator(Interpolators.ALPHA_OUT)
            .withEndAction { visibility = state }
    }

    // See CollapsedStatusBarFragment#show.
    private fun View.show(shouldAnimateChange: Boolean) {
        animate().cancel()
        if (visibility == View.VISIBLE && alpha >= 1f) {
            return
        }
        visibility = View.VISIBLE
        if (!shouldAnimateChange) {
            alpha = 1f
            return
        }
        animate()
            .alpha(1f)
            .setDuration(FADE_IN_DURATION.toLong())
            .setInterpolator(Interpolators.ALPHA_IN)
            .setStartDelay(FADE_IN_DELAY.toLong())
            // We need to clean up any pending end action from animateHide if we call both hide and
            // show in the same frame before the animation actually gets started.
            // cancel() doesn't really remove the end action.
            .withEndAction(null)

        // TODO(b/364360986): Synchronize the motion with the Keyguard fading if necessary.
    }

    inner class LyricController(val statusBar: View) :
        LyricViewController(statusBar.context, statusBar, statusBar.findViewById(R.id.clock)) {
        private val leftSide: View by lazy {
            statusBar.findViewById(R.id.status_bar_start_side_except_heads_up)
        }
        private val notificationIconArea: View by lazy {
            statusBar.findViewById(R.id.notification_icon_area)
        }
        private var canShowNotificationIcons = false
        private var canShowLyric = false
        private var previousLeftSideVisibility = View.VISIBLE
        private var hidingLeftSide = false
        private var hidingNotificationIcons = false

        fun hideInitially() {
            // GONE because this shouldn't take space in the layout
            overlayLyricView.hideInitially(state = View.GONE)
            inlineLyricView?.hideInitially(state = View.GONE)
        }

        fun adjustVisibility(model: VisibilityModel) {
            canShowLyric = model.visibility == View.VISIBLE
            if (model.visibility == View.VISIBLE) {
                showLyricView(model.shouldAnimateChange)
            } else {
                hideLyricView(model.shouldAnimateChange)
            }
        }

        fun updateNotificationIconsVisibility(model: VisibilityModel) {
            canShowNotificationIcons = model.visibility == View.VISIBLE
            if (!hidingNotificationIcons) {
                notificationIconArea.adjustVisibility(model)
            }
        }

        override fun showLyricView(animate: Boolean) {
            if (shouldShowLyricNow() && canShowLyric) {
                if (isClockRightMode) {
                    if (!hidingNotificationIcons) {
                        hidingNotificationIcons = true
                    }
                    notificationIconArea.visibility = View.GONE
                } else {
                    if (!hidingLeftSide) {
                        previousLeftSideVisibility = leftSide.visibility
                        hidingLeftSide = true
                    }
                    leftSide.visibility = View.INVISIBLE
                }
                lyricView.show(animate)
            }
        }

        override fun hideLyricView(animate: Boolean) {
            val hiddenState = if (isClockRightMode) View.GONE else View.INVISIBLE
            lyricView.hide(state = hiddenState, shouldAnimateChange = animate)
            if (isClockRightMode) {
                restoreNotificationIcons(animate)
            } else {
                restoreLeftSide(animate)
            }
        }

        override fun onLyricPositionChanged() {
            overlayLyricView.hide(state = View.GONE, shouldAnimateChange = false)
            inlineLyricView?.hide(state = View.GONE, shouldAnimateChange = false)
            restoreLeftSide(false)
            restoreNotificationIcons(false)
            if (shouldShowLyricNow() && canShowLyric) {
                showLyricView(false)
            }
        }

        fun releaseViews() {
            overlayLyricView.visibility = View.GONE
            inlineLyricView?.visibility = View.GONE
            restoreLeftSide(false)
            restoreNotificationIcons(false)
        }

        private fun restoreLeftSide(animate: Boolean) {
            if (!hidingLeftSide) return
            hidingLeftSide = false
            leftSide.visibility = previousLeftSideVisibility
        }

        private fun restoreNotificationIcons(animate: Boolean) {
            if (!hidingNotificationIcons) return
            hidingNotificationIcons = false
            notificationIconArea.visibility =
                if (canShowNotificationIcons) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val CLOCK_POSITION_RIGHT = 0
        private const val CLOCK_POSITION_CENTER = 1
        private const val CLOCK_POSITION_LEFT = 2
        /** Matches [Settings.System.STATUSBAR_CLOCK_CHIP] — home wallpaper thumbnail (live WP → black). */
        private const val CHIP_STYLE_WALLPAPER_THUMBNAIL = 13

        /** Animation durations for status bar. Used to be defined in the fragment */
        const val FADE_IN_DURATION = 320
        const val FADE_OUT_DURATION = 160
        const val FADE_IN_DELAY = 50
    }
}

/** Listener for various events that may affect the status bar's visibility. */
interface StatusBarVisibilityChangeListener {
    /**
     * Called when the status bar visibility might have changed due to the device moving to a
     * different state.
     */
    fun onStatusBarVisibilityMaybeChanged()

    /** Called when a transition from lockscreen to dream has started. */
    fun onTransitionFromLockscreenToDreamStarted()

    /**
     * Called when the scene state has changed such that the home status bar is newly allowed or no
     * longer allowed. See [HomeStatusBarViewModel.isHomeStatusBarAllowed].
     */
    fun onIsHomeStatusBarAllowedBySceneChanged(isHomeStatusBarAllowedByScene: Boolean)
}
