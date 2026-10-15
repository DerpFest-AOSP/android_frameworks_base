/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2026 DerpFest AOSP
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
package com.android.systemui.pulse

import android.content.Context
import android.media.session.PlaybackState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class PulseViewController @Inject constructor(
    private val context: Context,
    private val mediaSessionManager: MediaSessionManager,
) : PulseAudioDataProcessor.DataListener,
    MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    private val mainScope = MainScope()
    private var listenersRegistered = false

    private var isMediaPlaying = false
    private var bouncerShowingOrKeyguardDismissing = false
    private var keyguardShowing = false
    private var isDozing = false
    private var isScreenOff = false

    private val settingsRepository: PulseSettingsRepository =
        PulseSettingsRepository(context)

    private val view: PulseView =
        PulseView(context)

    private val audioProcessor: PulseAudioDataProcessor =
        PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

    private val bassHaptics: PulseBassHaptics =
        PulseBassHaptics(context)

    val pulseEnabled: Boolean
        get() = settingsRepository.isPulseEnabled()

    val ambientEnabled: Boolean
        get() = settingsRepository.isPulseAmbientEnabled()

    private val isCollapsed: Boolean
        get() = ScrimUtils.get().isPanelFullyCollapsed()

    private val hapticsMode: Int
        get() = settingsRepository.getPulseHapticsMode()

    var pulseRunning: Boolean = false
        private set

    private var appliedShow: Boolean? = null
    private var appliedKeepCapture: Boolean? = null

    init {
        INSTANCE = this

        view.initialize(settingsRepository)
        settingsRepository.setOnSettingsChangedListener { onSettingsChanged() }
        settingsRepository.startObserving()
        onSettingsChanged()
    }

    fun getPulseView(): PulseView = view

    private fun updateState() {
        if (!pulseEnabled) {
            pulseRunning = false
            return
        }
        pulseRunning = isMediaPlaying
                && !bouncerShowingOrKeyguardDismissing
                && isCollapsed
                && ((keyguardShowing && !isDozing && !isScreenOff)
                || (isDozing && ambientEnabled))
        updatePulse(pulseRunning)
    }

    private fun onSettingsChanged() {
        val enabled = pulseEnabled
        if (enabled && !listenersRegistered) {
            ScrimUtils.get().addListener(this)
            mediaSessionManager.addListener(this)
            listenersRegistered = true
        } else if (!enabled && listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            mediaSessionManager.removeListener(this)
            listenersRegistered = false
            pulseRunning = false
        }
        updateState()
        // Re-apply even when show state is unchanged so always-on haptics can keep capture alive.
        // Turning pulse off hides immediately; show and hide otherwise fade.
        updatePulse(pulseRunning, animated = enabled)
    }

    private fun updatePulse(show: Boolean, animated: Boolean = true) {
        val keepCapture = pulseEnabled && (show || hapticsMode > 1)
        if (show == appliedShow && keepCapture == appliedKeepCapture) return
        appliedShow = show
        appliedKeepCapture = keepCapture

        mainScope.launch {
            if (show || keepCapture) {
                audioProcessor.startCapture()
            }
            view.setShown(shown = show, animated = animated) {
                if (appliedShow != true && appliedKeepCapture != true) {
                    audioProcessor.stopCapture()
                    bassHaptics.reset()
                }
            }
        }
    }

    override fun onDataUpdate(data: PulseData) {
        if (hapticsMode > 0) {
            bassHaptics.process(data.fftBytes)
        }
        // Keep feeding frames through fade-out. pulseRunning is already false by then.
        if (view.isRendering) {
            mainScope.launch {
                view.updateVisualizerData(data)
            }
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        isMediaPlaying = state == PlaybackState.STATE_PLAYING
        updateState()
    }

    override fun onMediaColorsChanged(color: Int?) {
        if (pulseEnabled && color != null) view.onMediaColorsChanged(color)
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        keyguardShowing = showing
        updateState()
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        updateState()
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateState()
    }

    override fun onBarStateChanged(state: Int) {
        updateState()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateState()
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        updateState()
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        updateState()
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        updateState()
    }

    override fun onScreenTurnedOff() {
        isScreenOff = true
        updateState()
    }

    override fun onStartedWakingUp() {
        isScreenOff = false
        updateState()
    }

    override fun onUserChanged() {
        settingsRepository.invalidateCache()
        bassHaptics.reset()
        updateState()
    }

    fun destroy() {
        pulseRunning = false
        appliedShow = false
        appliedKeepCapture = false
        mainScope.cancel()
        view.setShown(shown = false, animated = false)
        settingsRepository.stopObserving()
        if (listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            mediaSessionManager.removeListener(this)
            listenersRegistered = false
        }
        audioProcessor.cleanup()
        bassHaptics.reset()
        if (INSTANCE === this) {
            INSTANCE = null
        }
    }

    companion object {
        private const val TAG = "PulseViewController"

        @Volatile
        private var INSTANCE: PulseViewController? = null

        @JvmStatic
        fun get(context: Context): PulseViewController {
            return INSTANCE ?: throw IllegalStateException(
                "PulseViewController not initialized"
            )
        }
    }
}
