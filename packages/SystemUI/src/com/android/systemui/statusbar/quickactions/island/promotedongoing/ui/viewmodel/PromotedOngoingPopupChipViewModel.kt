/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.promotedongoing.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * One status-bar chip per [IslandEvent.PromotedOngoing]. Opening a chip uses that notification's
 * own view, the same card the dynamic bar already opens.
 */
class PromotedOngoingPopupChipViewModel
@AssistedInject
constructor(
    private val axDynamicBarInteractor: AxDynamicBarInteractor,
) : IslandChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("PromotedOngoingPopupChipViewModel.hydrator")

    val chips: List<PopupChipModel.Shown> by
        hydrator.hydratedStateOf(
            traceName = "chips",
            initialValue = emptyList(),
            source =
                axDynamicBarInteractor.settings.isDynamicIslandOngoingActive.flatMapLatest { enabled
                    ->
                    if (!enabled) {
                        flowOf(emptyList())
                    } else {
                        axDynamicBarInteractor.uiState.map { uiState ->
                            uiState.events.filterIsInstance<IslandEvent.PromotedOngoing>().map {
                                toPopupChipModel(it)
                            }
                        }
                    }
                },
        )

    /** The island lists [chips]. This stays hidden so the same update is not added twice. */
    override val chip: PopupChipModel
        get() = PopupChipModel.Hidden(PopupChipId.PromotedOngoing)

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(event: IslandEvent.PromotedOngoing): PopupChipModel.Shown {
        val iconModel =
            event.appIcon?.let { drawable ->
                Icon.Loaded(drawable, ContentDescription.Loaded(event.appName))
            }
                ?: Icon.Resource(
                    resId = com.android.systemui.res.R.drawable.ic_info,
                    contentDescription = ContentDescription.Loaded(event.appName),
                )
        val chipText =
            if (event.progress >= 0f) {
                "${(event.progress * 100).toInt()}%"
            } else if (event.shortText.isNotEmpty()) {
                event.shortText
            } else {
                ""
            }
        return PopupChipModel.Shown(
            chipId = PopupChipId.PromotedOngoingItem(event.sbn.key),
            icons = listOf(ChipIcon(icon = iconModel)),
            chipText = chipText,
            colors = ColorsModel.DynamicIsland,
            contentDescription = event.appName,
            popupContent = PopupContentModel.PromotedOngoing(event),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): PromotedOngoingPopupChipViewModel
    }
}
