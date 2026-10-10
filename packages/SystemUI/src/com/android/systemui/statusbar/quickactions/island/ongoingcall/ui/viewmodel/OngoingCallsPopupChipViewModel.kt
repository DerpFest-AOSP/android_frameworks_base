/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.ongoingcall.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.statusbar.quickactions.island.ui.model.ChipIcon
import com.android.systemui.statusbar.quickactions.island.ui.model.ColorsModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipId
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupChipModel
import com.android.systemui.statusbar.quickactions.island.ui.model.PopupContentModel
import com.android.systemui.statusbar.quickactions.island.ui.viewmodel.IslandChipViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class OngoingCallsPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val axDynamicBarInteractor: AxDynamicBarInteractor,
) : IslandChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("OngoingCallsPopupChipViewModel.hydrator")

    private val timerFlow = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.OngoingCall),
            source =
                axDynamicBarInteractor.settings.isDynamicIslandCallsActive.flatMapLatest { enabled ->
                    if (!enabled) {
                        flowOf(PopupChipModel.Hidden(PopupChipId.OngoingCall))
                    } else {
                        axDynamicBarInteractor.uiState
                            .map { uiState ->
                                uiState.events.firstOrNull { it is IslandEvent.Call }
                                    as? IslandEvent.Call
                            }
                            .distinctUntilChanged()
                            .flatMapLatest { event ->
                                if (event == null) {
                                    flowOf(PopupChipModel.Hidden(PopupChipId.OngoingCall))
                                } else if (event.connected && event.callStartTimeMs > 0L) {
                                    timerFlow.map { currentTime ->
                                        toPopupChipModel(event, currentTime)
                                    }
                                } else {
                                    flowOf(toPopupChipModel(event, currentTime = 0L))
                                }
                            }
                    }
                },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(event: IslandEvent.Call, currentTime: Long): PopupChipModel {
        val iconModel =
            Icon.Resource(
                resId = com.android.systemui.res.R.drawable.ic_call,
                contentDescription = ContentDescription.Loaded(event.callerName ?: "Call"),
            )
        val chipText =
            if (event.connected && event.callStartTimeMs > 0L) {
                val elapsedSecs =
                    ((currentTime - event.callStartTimeMs) / 1000).coerceAtLeast(0)
                String.format("%d:%02d", elapsedSecs / 60, elapsedSecs % 60)
            } else {
                event.callerName?.takeIf { it.isNotBlank() }
                    ?: event.number?.takeIf { it.isNotBlank() }
                    ?: context.getString(
                        if (event.callType.contains("incoming", ignoreCase = true)) {
                            com.android.systemui.res.R.string.ax_dynamic_bar_incoming_call
                        } else {
                            com.android.systemui.res.R.string.ax_dynamic_bar_call
                        }
                    )
            }
        return PopupChipModel.Shown(
            chipId = PopupChipId.OngoingCall,
            icons = listOf(ChipIcon(icon = iconModel)),
            chipText = chipText,
            colors = ColorsModel.DynamicIsland,
            contentDescription = event.callerName,
            popupContent = PopupContentModel.OngoingCall(event),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): OngoingCallsPopupChipViewModel
    }
}
