/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.liveactivity.ui.compose

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.quickactions.island.liveactivity.shared.model.LiveActivityPopupModel
import com.android.systemui.statusbar.quickactions.island.liveactivity.shared.model.LiveActivityTime
import com.android.systemui.statusbar.quickactions.island.liveactivity.ui.viewmodel.LiveActivityPopupChipsViewModel
import com.android.systemui.statusbar.quickactions.island.shared.model.PopupActionModel
import kotlinx.coroutines.delay

private val PopupShape = RoundedCornerShape(28.dp)

/** An app's Live Update (promoted ongoing notification) as an island card. */
@Composable
fun LiveActivityPopup(model: LiveActivityPopupModel, modifier: Modifier = Modifier) {
    val accent = model.accent ?: Color(0xFF8AB4F8)
    val timeText = rememberLiveTimeText(model.time)
    Column(
        modifier =
            modifier
                .widthIn(min = 300.dp, max = 380.dp)
                .clip(PopupShape)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            model.icon?.let { Icon(icon = it, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    text = timeText ?: model.shortText ?: model.title ?: model.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary =
                    if (timeText != null || model.shortText != null) model.title ?: model.appName
                    else model.text ?: model.appName
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        model.progress?.let { progress ->
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.24f))
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(accent)
                )
            }
        }
        if (model.actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.actions.forEach { action -> ActionButton(action, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActionButton(action: PopupActionModel, modifier: Modifier) {
    Text(
        text = action.label.ifBlank { " " },
        modifier = modifier.clickable(onClick = action.onClick).padding(vertical = 8.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun rememberLiveTimeText(time: LiveActivityTime?): String? {
    if (time !is LiveActivityTime.Chronometer) {
        return when (time) {
            is LiveActivityTime.Paused -> DateUtils.formatElapsedTime(time.durationMs / 1_000L)
            else -> null
        }
    }
    val text by
        produceState(
            LiveActivityPopupChipsViewModel.formatChronometer(time),
            time.elapsedRealtimeMs,
            time.isCountDown,
        ) {
            while (true) {
                value = LiveActivityPopupChipsViewModel.formatChronometer(time)
                delay(1_000L - SystemClock.elapsedRealtime() % 1_000L)
            }
        }
    return text
}
