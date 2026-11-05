/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.liveactivity.shared.model

import androidx.compose.ui.graphics.Color
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.quickactions.island.shared.model.PopupActionModel

/** An app's promoted ongoing notification (an Android Live Update) shown in the island. */
data class LiveActivityPopupModel(
    val key: String,
    val packageName: String,
    val appName: String,
    val icon: Icon?,
    val title: String?,
    val text: String?,
    /** Short status the app wants in the status bar, like "5 min" or "Arriving". */
    val shortText: String?,
    val time: LiveActivityTime?,
    /** 0..1, or null when the notification has no progress. */
    val progress: Float?,
    val indeterminate: Boolean,
    val accent: Color?,
    val onOpen: (() -> Unit)?,
    val actions: List<PopupActionModel>,
)

sealed interface LiveActivityTime {
    /** Counts down to [elapsedRealtimeMs], or up from it, on the elapsed-realtime clock. */
    data class Chronometer(val elapsedRealtimeMs: Long, val isCountDown: Boolean) : LiveActivityTime

    /** A wall-clock time to show as-is, like an arrival time. */
    data class Clock(val currentTimeMs: Long) : LiveActivityTime

    /** A paused timer or stopwatch, showing [durationMs]. */
    data class Paused(val durationMs: Long) : LiveActivityTime
}
