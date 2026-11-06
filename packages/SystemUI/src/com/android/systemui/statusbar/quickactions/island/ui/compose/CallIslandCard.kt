/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.formatElapsedTime
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.res.R
import com.android.systemui.statusbar.quickactions.island.shared.toSendAction
import kotlinx.coroutines.delay

private val CallCardShape = RoundedCornerShape(36.dp)
private val CallButtonSize = 56.dp
private val CallEndRed = Color(0xFFFF453A)
private val CallAnswerGreen = Color(0xFF30D158)
private val OnCallCard = Color.White

/** The expanded island of a call: who it is, how long it has run, and the call's own buttons. */
@Composable
fun CallIslandCard(event: IslandEvent.Call, modifier: Modifier = Modifier) {
    val incoming = event.callType == "Phone:incoming"
    val name = event.callerName ?: event.number ?: stringResource(R.string.ax_dynamic_bar_call)
    val onAnswer = event.controls.answer.toSendAction()
    val onDecline = event.controls.decline.toSendAction()
    val onEnd = event.controls.hangUp.toSendAction()
    val onSpeaker = event.controls.speaker.toSendAction()
    val onMute = event.controls.mute.toSendAction()
    val onOpen = event.sbn.notification.contentIntent.toSendAction()
    Column(
        modifier =
            modifier
                .widthIn(min = 320.dp, max = 420.dp)
                .clip(CallCardShape)
                .background(Color.Black)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallAvatar(event, name, size = if (incoming) 48.dp else 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = name,
                    color = OnCallCard,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = callSubtitle(event, incoming),
                    color = OnCallCard.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (incoming) {
                onDecline?.let {
                    CallFilledButton(
                        Icons.Filled.CallEnd,
                        stringResource(R.string.ax_dynamic_bar_call_decline),
                        CallEndRed,
                        48.dp,
                        it,
                    )
                }
                onAnswer?.let {
                    CallFilledButton(
                        Icons.Filled.Call,
                        stringResource(R.string.ax_dynamic_bar_call_answer),
                        CallAnswerGreen,
                        48.dp,
                        it,
                    )
                }
            } else {
                onOpen?.let { open ->
                    CallFilledButton(
                        Icons.Filled.MoreHoriz,
                        stringResource(R.string.ax_dynamic_bar_call_more),
                        Color.White.copy(alpha = 0.16f),
                        40.dp,
                        open,
                    )
                }
            }
        }
        if (!incoming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallToggleButton(
                    Icons.Filled.VolumeUp,
                    stringResource(R.string.ax_dynamic_bar_call_speaker),
                    on = event.controls.speakerOn == true,
                    onClick = onSpeaker,
                )
                CallToggleButton(
                    if (event.controls.muted == true) Icons.Filled.MicOff else Icons.Filled.Mic,
                    stringResource(R.string.ax_dynamic_bar_call_mute),
                    on = event.controls.muted == true,
                    onClick = onMute,
                )
                onEnd?.let {
                    CallFilledButton(
                        Icons.Filled.CallEnd,
                        stringResource(R.string.ax_dynamic_bar_call_end),
                        CallEndRed,
                        CallButtonSize,
                        it,
                    )
                }
            }
        }
    }
}

@Composable
private fun callSubtitle(event: IslandEvent.Call, incoming: Boolean): String =
    when {
        event.connected && event.callStartTimeMs > 0L -> rememberCallDurationText(event.callStartTimeMs)
        incoming ->
            event.number?.takeIf { it != event.callerName }
                ?: stringResource(R.string.ax_dynamic_bar_incoming_call)
        else -> stringResource(R.string.ax_dynamic_bar_call_calling)
    }

@Composable
private fun rememberCallDurationText(startTimeMs: Long): String {
    var elapsedMs by remember(startTimeMs) {
        mutableLongStateOf((System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L))
    }
    LaunchedEffect(startTimeMs) {
        while (true) {
            delay(1_000L)
            elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
        }
    }
    return formatElapsedTime(elapsedMs)
}

@Composable
private fun CallAvatar(event: IslandEvent.Call, name: String, size: Dp) {
    Box(
        modifier =
            Modifier.size(size)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF9A9AA2), Color(0xFF5C5C63)))),
        contentAlignment = Alignment.Center,
    ) {
        val photo = event.callerPhoto
        val initials = initialsOf(name)
        when {
            photo != null ->
                Image(
                    bitmap = photo.toScaledBitmap(size),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            initials != null ->
                Text(
                    initials,
                    color = Color.White,
                    fontSize = (size.value * 0.38f).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            else ->
                Icon(
                    Icons.Filled.Call,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.45f),
                )
        }
    }
}

private fun initialsOf(name: String): String? {
    val words = name.split(' ', '-', '_').filter { it.firstOrNull()?.isLetter() == true }
    if (words.isEmpty()) return null
    return words.take(2).joinToString("") { it.first().uppercase() }
}

@Composable
private fun CallToggleButton(
    icon: ImageVector,
    label: String,
    on: Boolean,
    onClick: (() -> Unit)?,
) {
    Box(
        modifier =
            Modifier.size(CallButtonSize)
                .alpha(if (onClick == null) 0.35f else 1f)
                .clip(CircleShape)
                .background(if (on) OnCallCard else OnCallCard.copy(alpha = 0.14f))
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Switch, onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .semantics {
                    role = Role.Switch
                    contentDescription = label
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (on) Color.Black else OnCallCard,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun CallFilledButton(
    icon: ImageVector,
    label: String,
    color: Color,
    size: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(size)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}
