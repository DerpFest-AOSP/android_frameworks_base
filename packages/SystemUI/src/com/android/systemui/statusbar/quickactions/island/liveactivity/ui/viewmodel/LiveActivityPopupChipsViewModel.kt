/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.liveactivity.ui.viewmodel

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.android.internal.widget.NotificationProgressModel
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.chips.notification.domain.interactor.StatusBarNotificationChipsInteractor
import com.android.systemui.statusbar.chips.notification.domain.model.NotificationChipModel
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.Metric
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.quickactions.island.liveactivity.shared.model.LiveActivityPopupModel
import com.android.systemui.statusbar.quickactions.island.liveactivity.shared.model.LiveActivityTime
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.ONGOING_ACTIVITIES
import com.android.systemui.statusbar.quickactions.island.shared.DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.quickactions.island.shared.model.PopupActionModel
import com.android.systemui.statusbar.quickactions.island.shared.toActivityLaunchAction
import com.android.systemui.statusbar.quickactions.island.shared.toSendAction
import com.android.systemui.statusbar.quickactions.island.ui.model.ChipIcon
import com.android.systemui.statusbar.quickactions.island.ui.model.ColorsModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipId
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupContentModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Apps' promoted ongoing notifications (Android Live Updates: timers, navigation, deliveries) as
 * island chips. The stock status bar chip for each is dropped while the island shows it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveActivityPopupChipsViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    notifChipsInteractor: StatusBarNotificationChipsInteractor,
    private val notifCollection: CommonNotifCollection,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : ExclusiveActivatable() {
    private val hydrator = Hydrator("LiveActivityPopupChipsViewModel.hydrator")

    private val models: Flow<List<LiveActivityPopupModel>> =
        combine(
            notifChipsInteractor.allNotificationChips,
            observeDynamicIslandFeatureEnabled(context, ONGOING_ACTIVITIES),
        ) { chips, enabled ->
            if (!enabled) emptyList() else chips.mapNotNull { it.toModel() }
        }

    val chips: List<PopupChipModel.Shown> by
        hydrator.hydratedStateOf(
            traceName = "chips",
            initialValue = emptyList(),
            source =
                models.flatMapLatest { models ->
                    if (models.none { it.time is LiveActivityTime.Chronometer }) {
                        flowOf(models.map { it.toChip() })
                    } else {
                        flow {
                            while (true) {
                                emit(models.map { it.toChip() })
                                delay(1_000L - SystemClock.elapsedRealtime() % 1_000L)
                            }
                        }
                    }
                },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun NotificationChipModel.toModel(): LiveActivityPopupModel? {
        if (isScreenShareNotification) return null
        val content = promotedContent.privateVersion
        if (
            content.style == PromotedNotificationContentModel.Style.Call ||
                content.style == PromotedNotificationContentModel.Style.CollapsedCall
        ) {
            return null
        }
        val notification = notifCollection.getEntry(key)?.sbn?.notification ?: return null
        val metric = content.metrics?.firstOrNull()
        val metricTime =
            when (metric) {
                is Metric.TimeDifference.ElapsedRealtime ->
                    LiveActivityTime.Chronometer(metric.zeroElapsedRealtime, metric.isTimer)
                is Metric.TimeDifference.Instant ->
                    LiveActivityTime.Chronometer(
                        SystemClock.elapsedRealtime() +
                            (metric.zeroTime.toEpochMilli() - System.currentTimeMillis()),
                        metric.isTimer,
                    )
                is Metric.TimeDifference.Paused ->
                    LiveActivityTime.Paused(metric.pausedDuration.toMillis())
                else -> null
            }
        val metricText = (metric as? Metric.Text)?.textVariants?.firstOrNull()?.toString()
        val time =
            metricTime
                ?: when (val raw = content.time) {
                    is PromotedNotificationContentModel.When.Chronometer ->
                        LiveActivityTime.Chronometer(raw.elapsedRealtimeMillis, raw.isCountDown)
                    is PromotedNotificationContentModel.When.Time ->
                        LiveActivityTime.Clock(raw.currentTimeMillis)
                    null -> null
                }
        val newProgress = content.newProgress
        val progress =
            newProgress
                ?.takeIf { it.indeterminateColor == NotificationProgressModel.INVALID_COLOR }
                ?.let { p ->
                    if (p.progressMax > 0) p.progress.toFloat() / p.progressMax else null
                }
                ?: content.oldProgress?.let { p ->
                    if (p.max > 0) p.progress.toFloat() / p.max else null
                }
        val indeterminate =
            (newProgress != null &&
                newProgress.indeterminateColor != NotificationProgressModel.INVALID_COLOR) ||
                content.oldProgress?.isIndeterminate == true
        val accent =
            notification.color.takeIf { it != Notification.COLOR_DEFAULT }?.let { Color(it) }
        val icon =
            runCatching { notification.smallIcon?.loadDrawable(context) }
                .getOrNull()
                ?.let { Icon.Loaded(it, ContentDescription.Loaded(appName)) }
        return LiveActivityPopupModel(
            key = key,
            packageName = packageName,
            appName = appName,
            icon = icon,
            title = (content.title ?: metric?.label)?.toString()?.takeIf { it.isNotBlank() },
            text = content.text?.toString()?.takeIf { it.isNotBlank() },
            shortText = (content.shortCriticalText ?: metricText)?.takeIf { it.isNotBlank() },
            time = time,
            progress = progress?.coerceIn(0f, 1f),
            indeterminate = indeterminate,
            accent = accent,
            onOpen =
                notification.contentIntent.toActivityLaunchAction(
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                ),
            actions =
                notification.actions.orEmpty().take(MAX_ACTIONS).mapNotNull { action ->
                    val send = action.actionIntent.toSendAction() ?: return@mapNotNull null
                    PopupActionModel(label = action.title?.toString().orEmpty(), onClick = send)
                },
        )
    }

    private fun LiveActivityPopupModel.toChip(): PopupChipModel.Shown =
        PopupChipModel.Shown(
            chipId = PopupChipId.LiveActivity(key),
            icons = listOfNotNull(icon?.let { ChipIcon(icon = it, onClick = onOpen) }),
            chipText = chipText(),
            colors = ColorsModel.DynamicIsland,
            contentDescription = title ?: appName,
            popupContent = PopupContentModel.LiveActivity(this),
        )

    private fun LiveActivityPopupModel.chipText(): String? =
        when (val t = time) {
            is LiveActivityTime.Chronometer -> formatChronometer(t)
            is LiveActivityTime.Paused -> DateUtils.formatElapsedTime(t.durationMs / 1_000L)
            is LiveActivityTime.Clock ->
                shortText
                    ?: DateFormat.getTimeFormat(context).format(t.currentTimeMs).takeIf {
                        t.currentTimeMs > System.currentTimeMillis()
                    }
            null -> shortText
        }

    @AssistedFactory
    interface Factory {
        fun create(): LiveActivityPopupChipsViewModel
    }

    companion object {
        private const val MAX_ACTIONS = 3

        /** Time left on a countdown or time since the start of a count-up, as "14:54". */
        fun formatChronometer(time: LiveActivityTime.Chronometer): String {
            val now = SystemClock.elapsedRealtime()
            val ms =
                if (time.isCountDown) time.elapsedRealtimeMs - now else now - time.elapsedRealtimeMs
            val seconds =
                if (time.isCountDown) (ms.coerceAtLeast(0L) + 999L) / 1_000L else ms / 1_000L
            return DateUtils.formatElapsedTime(seconds.coerceAtLeast(0L))
        }
    }
}
