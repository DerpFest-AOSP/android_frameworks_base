/*
 * Copyright (C) 2024 The Android Open Source Project
 *           (C) 2026 The LibreMobileOS Foundation
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

package com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel

import android.content.Context
import android.media.AppVolume
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.res.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** Models a particular slider state. */
class AppVolumeSliderViewModel
@AssistedInject
constructor(
    @Assisted val packageName: String,
    @Assisted private val coroutineScope: CoroutineScope,
    @UiBackground private val uiBackgroundContext: CoroutineContext,
    private val context: Context,
    private val audioVolumeInteractor: AudioVolumeInteractor,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) : SliderViewModel {

    private val volumeChanges = MutableStateFlow<Float?>(null)
    private val muteChanges = MutableStateFlow<Boolean?>(null)
    private val sliderLock = Any()

    private val appLabel: String by lazy { resolveAppLabel() }
    val appIcon = MutableStateFlow<Icon.Loaded?>(null)
    private val volumeOnIcon: Icon.Loaded by lazy { loadTrackIcon(R.drawable.ic_volume_media) }
    private val volumeOffIcon: Icon.Loaded by lazy {
        loadTrackIcon(R.drawable.ic_volume_media_mute)
    }

    override val slider = MutableStateFlow<SliderState>(SliderState.Empty)

    init {
        volumeChanges
            .filterNotNull()
            .onEach { newValue ->
                synchronized(sliderLock) {
                    val current = slider.value as? State
                    if (current != null) {
                        slider.value =
                            current.copy(
                                value = newValue,
                                icon = trackIcon(current.isMuted, newValue),
                                a11yStateDescription = describe(newValue, current.isMuted),
                            )
                    }
                }
                audioVolumeInteractor.setAppVolume(packageName, newValue)
            }
            .launchIn(coroutineScope)

        muteChanges
            .filterNotNull()
            .onEach { isMuted ->
                synchronized(sliderLock) {
                    val current = slider.value as? State
                    if (current != null) {
                        val shown = if (isMuted) 0f else current.value
                        slider.value =
                            current.copy(
                                value = shown,
                                isMuted = isMuted,
                                icon = trackIcon(isMuted, shown),
                                a11yStateDescription = describe(shown, isMuted),
                            )
                    }
                }
                audioVolumeInteractor.setAppMuted(packageName, isMuted)
            }
            .launchIn(coroutineScope)

        audioVolumeInteractor
            .getAppVolume(packageName)
            .filterNotNull()
            .onEach { appVolume ->
                val remoteState =
                    withContext(uiBackgroundContext) {
                        if (appIcon.value == null) {
                            appIcon.value = resolveAppIcon()
                        }
                        remoteState(appVolume)
                    }
                synchronized(sliderLock) {
                    if (isRequestInFlight(appVolume)) return@onEach
                    clearCaughtUpRequests(appVolume)
                    slider.value = remoteState
                }
            }
            .launchIn(coroutineScope)
    }

    override fun onValueChanged(state: SliderState, newValue: Float) {
        synchronized(sliderLock) {
            volumeChanges.tryEmit(newValue)
            if (newValue > 0f && (state as? State)?.isMuted == true) {
                muteChanges.tryEmit(false)
            }
        }
    }

    override fun onValueChangeFinished() {}

    override fun toggleMuted(state: SliderState) {
        val appState = state as? State ?: return
        synchronized(sliderLock) { muteChanges.tryEmit(!appState.isMuted) }
    }

    override fun getSliderHapticsViewModelFactory(): SliderHapticsViewModel.Factory? =
        if (slider.value != SliderState.Empty) hapticsViewModelFactory else null

    /**
     * A poll can still be carrying the volume from before this drag. Keep the thumb where the user
     * put it until [AppVolume] reports that value, then accept later changes again.
     */
    private fun isRequestInFlight(appVolume: AppVolume): Boolean {
        val requestedVolume = volumeChanges.value
        val requestedMute = muteChanges.value
        if (requestedVolume != null && !volumesMatch(requestedVolume, appVolume.volume)) {
            return true
        }
        return requestedMute != null && requestedMute != appVolume.isMuted
    }

    private fun clearCaughtUpRequests(appVolume: AppVolume) {
        val requestedVolume = volumeChanges.value
        if (requestedVolume != null && volumesMatch(requestedVolume, appVolume.volume)) {
            volumeChanges.value = null
        }
        val requestedMute = muteChanges.value
        if (requestedMute != null && requestedMute == appVolume.isMuted) {
            muteChanges.value = null
        }
    }

    private fun volumesMatch(requested: Float, actual: Float): Boolean =
        abs(requested - actual) < VOLUME_EPSILON

    private fun remoteState(appVolume: AppVolume): State {
        val value = if (appVolume.isMuted) 0f else appVolume.volume
        return State(
            value = value,
            isMuted = appVolume.isMuted,
            icon = trackIcon(appVolume.isMuted, value),
            label = appLabel,
            a11yStateDescription = describe(value, appVolume.isMuted),
        )
    }

    private fun describe(value: Float, isMuted: Boolean): String =
        if (isMuted) {
            context.getString(R.string.volume_panel_hint_muted)
        } else {
            context.getString(R.string.app_volume_percent, (value * 100).roundToInt())
        }

    private fun resolveAppLabel(): String =
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            )
                .toString()
        }
            .getOrElse { packageName }

    private fun resolveAppIcon(): Icon.Loaded =
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrElse { context.packageManager.getDefaultActivityIcon() }
            .asIcon(isBitmapImage = true)

    private fun trackIcon(isMuted: Boolean, value: Float): Icon.Loaded =
        if (isMuted || value == 0f) volumeOffIcon else volumeOnIcon

    private fun loadTrackIcon(resId: Int): Icon.Loaded =
        Icon.Loaded(
            drawable = requireNotNull(context.getDrawable(resId)),
            contentDescription = null,
            resId = resId,
        )

    private data class State(
        override val value: Float,
        override val icon: Icon.Loaded?,
        override val label: String,
        override val a11yStateDescription: String,
        val isMuted: Boolean,
    ) : SliderState {
        override val valueRange: ClosedFloatingPointRange<Float> = 0f..1f
        override val step: Float = 0.05f // 5%
        override val hapticFilter: SliderHapticFeedbackFilter = SliderHapticFeedbackFilter()
        override val isEnabled: Boolean = true
        override val disabledMessage: String? = null
        override val isMutable: Boolean = true
        override val a11yContentDescription: String = label
        override val a11yClickDescription: String? = null
    }

    @AssistedFactory
    interface Factory {
        fun create(packageName: String, coroutineScope: CoroutineScope): AppVolumeSliderViewModel
    }

    private companion object {
        const val VOLUME_EPSILON = 0.001f
    }
}
