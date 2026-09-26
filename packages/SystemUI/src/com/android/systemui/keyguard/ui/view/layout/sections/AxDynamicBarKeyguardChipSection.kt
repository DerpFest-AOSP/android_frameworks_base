package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipState
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.axdynamicbar.ui.compose.AxDynamicBarKeyguardChip
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.util.ScrimUtils
import com.android.systemui.util.Utils
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine

class AxDynamicBarKeyguardChipSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: AxDynamicBarChipViewModel,
    private val indicationController: KeyguardIndicationController,
) : KeyguardSection() {

    private val chipViewId = R.id.ax_dynamic_bar_keyguard_chip
    private var bindHandle: DisposableHandle? = null
    private var expansionHandle: DisposableHandle? = null
    private var enforceAction: Runnable? = null

    private var isCurrentlyHiding = false

    override fun addViews(constraintLayout: ConstraintLayout) {
        val composeView = ComposeView(context).apply { id = chipViewId }
        constraintLayout.addView(composeView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val composeView: ComposeView = constraintLayout.requireViewById(chipViewId)

        if (shouldSuppressIndication()) {
            indicationController.setSuppressIndication(true)
        }

        composeView.setContent {
            PlatformTheme {
                AxDynamicBarKeyguardChip(viewModel = viewModel)
            }
        }

        bindHandle = composeView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(
                    viewModel.isOnKeyguard,
                    viewModel.isEnabled,
                    viewModel.isKeyguardEnabled,
                    viewModel.isLockscreenMediaEnabled,
                    viewModel.isLockscreenMediaLyricsEnabled,
                    viewModel.chipState,
                    viewModel.keyguardBatteryChipMode,
                    viewModel.keyguardBatteryInfo,
                    viewModel.isDozing,
                ) { args ->
                    val onKeyguard = args[0] as Boolean
                    val enabled = args[1] as Boolean
                    val keyguardEnabled = args[2] as Boolean
                    val mediaEnabled = args[3] as Boolean
                    val lyricsEnabled = args[4] as Boolean
                    val state = args[5] as AxDynamicBarChipState?
                    val batteryChipMode = args[6] as Int
                    val batteryInfo = args[7] as KeyguardBatteryInfo
                    val isDozing = args[8] as Boolean
                    suppressesKeyguardIndication(
                        onKeyguard = onKeyguard,
                        enabled = enabled,
                        keyguardEnabled = keyguardEnabled,
                        mediaEnabled = mediaEnabled,
                        lyricsEnabled = lyricsEnabled,
                        state = state,
                        batteryChipMode = batteryChipMode,
                        batteryInfo = batteryInfo,
                        isDozing = isDozing,
                    )
                }.collect { indicationController.setSuppressIndication(it) }
            }
        }

        expansionHandle = composeView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(viewModel.isKeyguardExpanded, viewModel.isLowUdfps) { expanded, lowUdfps ->
                    expanded to lowUdfps
                }.collect { (expanded, lowUdfps) ->
                    isCurrentlyHiding = expanded
                    enforceHiddenViews(constraintLayout, expanded)

                    enforceAction?.let { ScrimUtils.get().removeKeyguardPreDrawAction(it) }
                    enforceAction = if (expanded) {
                        Runnable { enforceHiddenViews(constraintLayout, true) }.also {
                            ScrimUtils.get().addKeyguardPreDrawAction(it)
                        }
                    } else null

                    val lp = composeView.layoutParams as ConstraintLayout.LayoutParams
                    if (expanded) {
                        lp.width = ConstraintLayout.LayoutParams.MATCH_PARENT
                        lp.height = 0
                        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.topMargin = Utils.getStatusBarHeaderHeightKeyguard(context)
                        lp.bottomToTop = R.id.start_button
                        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.bottomMargin = 0

                        lp.topToBottom = -1
                        lp.bottomToBottom = -1
                        lp.startToEnd = -1
                        lp.endToStart = -1
                    } else if (lowUdfps) {
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.topMargin = 0

                        lp.bottomToTop = R.id.device_entry_icon_view
                        lp.bottomMargin = (CHIP_ABOVE_LOCK_MARGIN_DP * context.resources.displayMetrics.density + 0.5f).toInt()
                        lp.bottomToBottom = -1
                        lp.topToTop = -1

                        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.startToEnd = -1
                        lp.endToStart = -1

                        lp.topToBottom = -1
                    } else {
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.topMargin = 0

                        lp.bottomToBottom = R.id.start_button
                        lp.bottomMargin = 0
                        lp.bottomToTop = -1
                        lp.topToTop = -1

                        lp.startToEnd = R.id.start_button
                        lp.endToStart = R.id.end_button
                        lp.startToStart = -1
                        lp.endToEnd = -1

                        lp.topToBottom = -1
                    }
                    composeView.layoutParams = lp
                }
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val expanded = viewModel.isKeyguardExpanded.value
        val lowUdfps = viewModel.isLowUdfps.value
        constraintSet.apply {
            if (expanded) {
                constrainWidth(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                constrainHeight(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                connect(chipViewId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP,
                    Utils.getStatusBarHeaderHeightKeyguard(context))
                connect(chipViewId, ConstraintSet.BOTTOM, R.id.start_button, ConstraintSet.TOP)
                connect(chipViewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(chipViewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            } else if (lowUdfps) {
                constrainWidth(chipViewId, ViewGroup.LayoutParams.WRAP_CONTENT)
                constrainHeight(chipViewId, ViewGroup.LayoutParams.WRAP_CONTENT)
                val margin = (CHIP_ABOVE_LOCK_MARGIN_DP * context.resources.displayMetrics.density + 0.5f).toInt()
                connect(chipViewId, ConstraintSet.BOTTOM, R.id.device_entry_icon_view, ConstraintSet.TOP, margin)
                connect(chipViewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(chipViewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            } else {
                constrainWidth(chipViewId, ViewGroup.LayoutParams.WRAP_CONTENT)
                constrainHeight(chipViewId, ViewGroup.LayoutParams.WRAP_CONTENT)
                connect(chipViewId, ConstraintSet.BOTTOM, R.id.start_button, ConstraintSet.BOTTOM)
                connect(chipViewId, ConstraintSet.START, R.id.start_button, ConstraintSet.END)
                connect(chipViewId, ConstraintSet.END, R.id.end_button, ConstraintSet.START)
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        enforceAction?.let { ScrimUtils.get().removeKeyguardPreDrawAction(it) }
        enforceAction = null
        expansionHandle?.dispose()
        expansionHandle = null
        bindHandle?.dispose()
        bindHandle = null
        indicationController.setSuppressIndication(false)
        constraintLayout.removeView(chipViewId)
    }

    private fun suppressesKeyguardIndication(
        onKeyguard: Boolean,
        enabled: Boolean,
        keyguardEnabled: Boolean,
        mediaEnabled: Boolean,
        lyricsEnabled: Boolean,
        state: AxDynamicBarChipState?,
        batteryChipMode: Int,
        batteryInfo: KeyguardBatteryInfo,
        isDozing: Boolean,
    ): Boolean {
        val showNormalDynamicBar = enabled && keyguardEnabled && state != null
        val showLockscreenMedia = mediaEnabled && state != null && state.event is IslandEvent.Media
        val showLockscreenLyrics = lyricsEnabled && state != null && state.event is IslandEvent.Media
        val batteryChipVisible =
            batteryChipMode > 0 &&
                (batteryChipMode == 2 || (batteryChipMode == 1 && batteryInfo.isCharging))
        val showBatteryChip =
            state == null && batteryChipVisible && enabled && keyguardEnabled && !isDozing
        return onKeyguard &&
            (showNormalDynamicBar || showLockscreenMedia || showLockscreenLyrics || showBatteryChip)
    }

    private fun shouldSuppressIndication(): Boolean =
        suppressesKeyguardIndication(
            onKeyguard = viewModel.isOnKeyguard.value,
            enabled = viewModel.isEnabled.value,
            keyguardEnabled = viewModel.isKeyguardEnabled.value,
            mediaEnabled = viewModel.isLockscreenMediaEnabled.value,
            lyricsEnabled = viewModel.isLockscreenMediaLyricsEnabled.value,
            state = viewModel.chipState.value,
            batteryChipMode = viewModel.keyguardBatteryChipMode.value,
            batteryInfo = viewModel.keyguardBatteryInfo.value,
            isDozing = viewModel.isDozing.value,
        )

    companion object {
        private const val CHIP_ABOVE_LOCK_MARGIN_DP = 12f
    }

    private val preserveOnExpandIds = setOf(
        chipViewId,
        R.id.start_button,
        R.id.end_button,
    )

    private fun enforceHiddenViews(constraintLayout: ConstraintLayout, hide: Boolean) {
        for (i in 0 until constraintLayout.childCount) {
            val child = constraintLayout.getChildAt(i)
            if (child.id in preserveOnExpandIds) continue
            val target = if (hide) View.INVISIBLE else View.VISIBLE
            if (child.visibility != target) child.visibility = target
        }
        constraintLayout.rootView
            .findViewById<View>(R.id.shared_notification_container)
            ?.let { v ->
                val target = if (hide) View.INVISIBLE else View.VISIBLE
                if (v.visibility != target) v.visibility = target
            }
    }
}
