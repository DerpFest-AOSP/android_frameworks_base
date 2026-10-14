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

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.media.AudioManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QsLayoutMode
import com.android.systemui.qs.panels.data.repository.QSColumnsRepository
import com.android.systemui.qs.panels.domain.interactor.QSPreferencesInteractor
import com.android.systemui.qs.panels.domain.interactor.QsBrightnessSliderVisibilityInteractor
import com.android.systemui.qs.panels.domain.interactor.QsVolumeSliderVisibilityInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.LocalDeferQsTileContent
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditModeLayoutTabViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditModeTabsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.qs.ui.composable.QsVolumeSliderRow
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.DualShadeFlag
import com.android.systemui.volume.panel.component.volume.domain.model.SliderType
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val detailsViewModel: DetailsViewModel,
    private val iconTilesViewModel: IconTilesViewModel,
    override val viewModelFactory: InfiniteGridViewModel.Factory,
    private val textFeedbackContentViewModelFactory: TextFeedbackContentViewModel.Factory,
    private val tileHapticsViewModelFactory: TileHapticsViewModel.Factory,
    private val editModeTabs: EditModeTabs,
    private val editModeLayoutTab: EditModeLayoutTab,
    private val editModeLayoutTabViewModel: EditModeLayoutTabViewModel,
    private val qsPreferencesInteractor: QSPreferencesInteractor,
    private val brightnessSliderVisibilityInteractor: QsBrightnessSliderVisibilityInteractor,
    private val volumeSliderVisibilityInteractor: QsVolumeSliderVisibilityInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val brightnessSliderViewModelFactory: BrightnessSliderViewModel.Factory,
    private val audioStreamSliderViewModelFactory: AudioStreamSliderViewModel.Factory,
    private val qsColumnsRepository: QSColumnsRepository,
) : PaginatableGridLayout {

    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModelFactory.create()
            }

        val context = LocalContext.current
        val textFeedbackViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid", key = context) {
                textFeedbackContentViewModelFactory.create(context)
            }

        val classicStyle = rememberQSPanelStyle()
        val classicColumns by
            qsColumnsRepository.classicColumns.collectAsStateWithLifecycle(
                initialValue = integerResource(R.integer.quick_settings_num_columns_classic)
            )
        val baseColumns = viewModel.columnsWithMediaViewModel.columns
        val columns = if (classicStyle) classicColumns else baseColumns
        val largeTilesSpan = if (classicStyle) 1 else viewModel.columnsWithMediaViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        // Tiles or largeTiles may be updated while this is composed, so listen to any changes
        val sizedTiles =
            remember(tiles, largeTiles, largeTilesSpan, classicStyle) {
                tiles.map {
                    SizedTileImpl(
                        it,
                        if (classicStyle) 1
                        else if (largeTiles.contains(it.spec)) largeTilesSpan else 1,
                    )
                }
            }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val bounceables =
            remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
        val spans by remember(sizedTiles) { derivedStateOf { sizedTiles.fastMap { it.width } } }
        val deferTileContent = LocalDeferQsTileContent.current
        var readyTileCount by remember {
            mutableIntStateOf(if (deferTileContent) 0 else Int.MAX_VALUE)
        }
        if (deferTileContent) {
            LaunchedEffect(sizedTiles.size) {
                var shown = readyTileCount
                while (shown < sizedTiles.size) {
                    withFrameNanos {}
                    shown = minOf(shown + TilesComposedPerFrame, sizedTiles.size)
                    readyTileCount = shown
                }
            }
        }
        val placeholderHeight =
            if (!deferTileContent) {
                0.dp
            } else if (classicStyle && !rememberQSTileLabelHide()) {
                CommonTileDefaults.TileHeight + 8.dp
            } else {
                CommonTileDefaults.TileHeight
            }
        VerticalSpannedGrid(
            columns = columns,
            columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
            rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
            spans = spans,
            keys = { sizedTiles[it].tile.spec },
            modifier = modifier,
        ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
            val it = sizedTiles[spanIndex]

            Element(it.tile.spec.toElementKey(), Modifier) {
                if (!deferTileContent || spanIndex < readyTileCount) {
                    Tile(
                        tile = it.tile,
                        iconOnly = classicStyle || iconTilesViewModel.isIconTile(it.tile.spec),
                        squishiness = { squishiness },
                        tileHapticsViewModelFactory = tileHapticsViewModelFactory,
                        coroutineScope = scope,
                        bounceableInfo =
                            bounceables.bounceableInfo(
                                it,
                                index = spanIndex,
                                column = column,
                                columns = columns,
                                isFirstInRow = isFirstInColumn,
                                isLastInRow = isLastInColumn,
                            ),
                        detailsViewModel = detailsViewModel,
                        isVisible = listening,
                        requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                        enableRevealEffect = enableRevealEffect,
                    )
                } else {
                    Spacer(Modifier.fillMaxWidth().height(placeholderHeight))
                }
            }
        }

        TileListener(tiles, listening)
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
        onSetTiles: (List<TileSpec>) -> Unit,
        onStopEditing: () -> Unit,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModelFactory.create()
            }
        val columnsViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.columnsWithMediaViewModelFactory.createWithoutMediaTracking()
            }
        val snapshotViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.snapshotViewModelFactory.create()
            }
        val topBarActionsViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.editTopBarActionsViewModelFactory.create()
            }
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val dialogDelegate =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.resetDialogDelegateFactory.create {
                    // Clear the stack of snapshots on reset
                    snapshotViewModel.clearStack()

                    // Automatically scroll to the top on reset
                    coroutineScope.launch { scrollState.animateScrollTo(0) }
                }
            }
        val showDualShadeSetting =
            DualShadeFlag.isEnabled &&
                LocalResources.current.getBoolean(
                    com.android.settingslib.R.bool.config_useDualShadeSetting
                )
        val actions =
            remember(topBarActionsViewModel, showDualShadeSetting) {
                topBarActionsViewModel.actions(showDualShadeSetting).toMutableStateList()
            }
        val classicStyle = rememberQSPanelStyle()
        val classicEditColumns by
            qsColumnsRepository.classicColumns.collectAsStateWithLifecycle(
                initialValue = integerResource(R.integer.quick_settings_num_columns_classic)
            )
        val columns = if (classicStyle) classicEditColumns else columnsViewModel.columns
        val largeTilesSpan = if (classicStyle) 1 else columnsViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState

        val currentTiles by rememberUpdatedState(tiles.filter { it.isCurrent })
        val listState =
            remember(columns, largeTilesSpan, classicStyle) {
                EditTileListState(
                    currentTiles,
                    largeTiles,
                    columns = columns,
                    largeTilesSpan = largeTilesSpan,
                )
            }
        LaunchedEffect(currentTiles, largeTiles) { listState.updateTiles(currentTiles, largeTiles) }

        val editModeTabsViewModel = remember { EditModeTabsViewModel() }
        val layoutBrightnessSliderViewModel =
            rememberViewModel("InfiniteGridLayout.EditLayoutBrightness") {
                brightnessSliderViewModelFactory.create(supportsMirroring = false)
            }
        val layoutVolumeSliderViewModel =
            remember(coroutineScope) {
                audioStreamSliderViewModelFactory.create(
                    AudioStreamSliderViewModel.FactoryAudioStreamWrapper(
                        SliderType.Stream(AudioStream(AudioManager.STREAM_MUSIC)).stream
                    ),
                    coroutineScope,
                )
            }
        if (QsLayoutMode.isEnabled) {
            LaunchedEffect(editModeLayoutTabViewModel) {
                qsPreferencesInteractor.shadeComponents.collect { order ->
                    editModeLayoutTabViewModel.setComponents(order)
                }
            }
            LaunchedEffect(editModeLayoutTabViewModel) {
                var wasDragging = false
                snapshotFlow {
                        (editModeLayoutTabViewModel.dragState != null) to
                            editModeLayoutTabViewModel.components.toList()
                    }
                    .collect { (dragging, order) ->
                        if (wasDragging && !dragging) {
                            qsPreferencesInteractor.setShadeComponents(order)
                        }
                        wasDragging = dragging
                    }
            }
        }

        val brightnessVisibility by
            brightnessSliderVisibilityInteractor.visibility.collectAsStateWithLifecycle()
        val volumeVisibility by
            volumeSliderVisibilityInteractor.visibility.collectAsStateWithLifecycle()
        val shadeMode by shadeModeInteractor.shadeMode.collectAsStateWithLifecycle()

        DefaultEditTileGrid(
            listState = listState,
            allTiles = tiles,
            modifier = modifier,
            scrollState = scrollState,
            snapshotViewModel = snapshotViewModel,
            onStopEditing = onStopEditing,
            topBarActions = actions,
            editModeTabs = editModeTabs.takeIf { QsLayoutMode.isEnabled },
            editModeTabsViewModel = editModeTabsViewModel.takeIf { QsLayoutMode.isEnabled },
            editModeLayoutTab = editModeLayoutTab.takeIf { QsLayoutMode.isEnabled },
            editModeLayoutTabViewModel =
                editModeLayoutTabViewModel.takeIf { QsLayoutMode.isEnabled },
            brightnessVisibility = brightnessVisibility,
            onBrightnessVisibilityChange = brightnessSliderVisibilityInteractor::setVisibility,
            volumeVisibility = volumeVisibility,
            onVolumeVisibilityChange = volumeSliderVisibilityInteractor::setVisibility,
            isDualShade = shadeMode is ShadeMode.Dual,
            layoutBrightness = {
                BrightnessSliderContainer(
                    viewModel = layoutBrightnessSliderViewModel,
                    containerColors =
                        ContainerColors(
                            Color.Transparent,
                            ContainerColors.defaultContainerColor,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            layoutVolume = {
                QsVolumeSliderRow(
                    viewModel = layoutVolumeSliderViewModel,
                    onSettingsClicked = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) { action ->
            // Opening the dialog doesn't require a snapshot
            if (action != EditAction.ResetGrid) {
                snapshotViewModel.takeSnapshot(currentTiles.map { it.tileSpec }, largeTiles)
            }

            when (action) {
                is EditAction.AddTile -> {
                    onAddTile(action.tileSpec, listState.tileSpecs().size)
                }
                is EditAction.InsertTile -> {
                    onAddTile(action.tileSpec, action.position)
                }
                is EditAction.RemoveTile -> {
                    onRemoveTile(action.tileSpec)
                }
                EditAction.ResetGrid -> {
                    dialogDelegate.showDialog()
                }
                is EditAction.ResizeTile -> {
                    iconTilesViewModel.resize(action.tileSpec, toIcon = action.toIcon)
                }
                is EditAction.SetTiles -> {
                    onSetTiles(action.tileSpecs)
                }
            }
        }
    }
}

/** How many real tiles to compose on each frame while a pull-down is opening the grid. */
private const val TilesComposedPerFrame = 4
