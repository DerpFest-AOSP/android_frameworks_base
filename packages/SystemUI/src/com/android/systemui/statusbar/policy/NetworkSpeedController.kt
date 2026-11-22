/*
 * SPDX-FileCopyrightText: AxionOS Project
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.policy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.provider.Settings
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.StatusBarIconControllerImplEx
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedIconState
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class NetworkSpeedController @Inject constructor(
    private val context: Context,
    private val connectivityManager: ConnectivityManager,
    private val secureSettings: SecureSettings,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val iconControllerEx: StatusBarIconControllerImplEx,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : CoreStartable {

    @Volatile private var isSwitchOn = false
    @Volatile private var isConnected = false
    @Volatile private var thresholdBytes = DEFAULT_THRESHOLD_KB * KB
    private var networkVisibility = false

    private var lastTime = 0L
    private var lastTotalBytes = 0L

    private val internetNetworks = CopyOnWriteArraySet<Network>()

    private val scope = CoroutineScope(bgDispatcher + SupervisorJob())
    private var speedUpdateJob: Job? = null

    private val _iconState = MutableStateFlow<NetworkSpeedIconState?>(null)
    val iconState: StateFlow<NetworkSpeedIconState?> = _iconState.asStateFlow()

    private val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetwork(network, connectivityManager.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            updateNetwork(network, caps)
        }

        override fun onLost(network: Network) {
            internetNetworks.remove(network)
            updateConnectionState(internetNetworks.isNotEmpty())
        }
    }

    override fun start() {
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        connectivityManager.activeNetwork?.let { network ->
            updateNetwork(network, connectivityManager.getNetworkCapabilities(network))
        }

        scope.launch {
            migrateHideList()
            detachFromTunerHideList()
            secureSettings
                .observerFlow(
                    Settings.Secure.STATUS_BAR_SHOW_NETWORK_SPEED,
                    Settings.Secure.STATUS_BAR_NETWORK_SPEED_THRESHOLD,
                )
                .onStart { emit(Unit) }
                .map { readConfig() }
                .distinctUntilChanged()
                .flowOn(bgDispatcher)
                .collect { applyConfig(it) }
        }

        keyguardUpdateMonitor.registerCallback(
            object : KeyguardUpdateMonitorCallback() {
                override fun onUserSwitchComplete(newUserId: Int) {
                    applyConfig(readConfig())
                }
            }
        )
    }

    /**
     * Older builds hid this slot by default. Opening any other status-bar tuner switch then
     * persisted that hide list, so later removing it from config would not unhide existing
     * devices. Strip that accidental hide once before [detachFromTunerHideList] treats a
     * remaining entry as an explicit off.
     */
    private fun migrateHideList() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_PREF, false)) return
        val hideListStr = secureSettings.getString(ICON_HIDE_LIST)
        if (hideListStr != null) {
            val hideList = hideListStr.split(",").filter { it.isNotEmpty() }.toMutableList()
            if (hideList.remove(SLOT_NETWORK_SPEED)) {
                secureSettings.putString(ICON_HIDE_LIST, hideList.joinToString(","))
            }
        }
        prefs.edit().putBoolean(MIGRATION_PREF, true).apply()
    }

    /**
     * The tuner switch stored visibility in [ICON_HIDE_LIST]. DerpFestParts now owns that
     * switch, so a leftover hide is copied into [Settings.Secure.STATUS_BAR_SHOW_NETWORK_SPEED]
     * once and the slot is dropped from the list. Later list changes do not override a setting
     * that has already been written.
     */
    private fun detachFromTunerHideList() {
        val hideListStr = secureSettings.getString(ICON_HIDE_LIST) ?: return
        val hideList = hideListStr.split(",").filter { it.isNotEmpty() }.toMutableList()
        if (!hideList.remove(SLOT_NETWORK_SPEED)) return
        secureSettings.putString(ICON_HIDE_LIST, hideList.joinToString(","))
        if (secureSettings.getString(Settings.Secure.STATUS_BAR_SHOW_NETWORK_SPEED) == null) {
            secureSettings.putInt(Settings.Secure.STATUS_BAR_SHOW_NETWORK_SPEED, 0)
        }
    }

    private fun readConfig(): IndicatorConfig {
        val enabled = secureSettings.getInt(
            Settings.Secure.STATUS_BAR_SHOW_NETWORK_SPEED, 1) != 0
        val thresholdKb = secureSettings.getInt(
            Settings.Secure.STATUS_BAR_NETWORK_SPEED_THRESHOLD, DEFAULT_THRESHOLD_KB)
            .coerceAtLeast(0)
        return IndicatorConfig(enabled, thresholdKb)
    }

    private fun applyConfig(config: IndicatorConfig) {
        val enabledChanged = isSwitchOn != config.enabled
        isSwitchOn = config.enabled
        thresholdBytes = config.thresholdKb * KB
        if (enabledChanged) {
            reset()
        }
        restartSpeedUpdates()
    }

    private fun hasInternet(caps: NetworkCapabilities?): Boolean {
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateNetwork(network: Network, caps: NetworkCapabilities?) {
        if (hasInternet(caps)) {
            internetNetworks.add(network)
        } else {
            internetNetworks.remove(network)
        }
        updateConnectionState(internetNetworks.isNotEmpty())
    }

    private fun updateConnectionState(connected: Boolean) {
        isConnected = connected
        restartSpeedUpdates()
    }

    private fun restartSpeedUpdates() {
        speedUpdateJob?.cancel()
        if (isConnected && isSwitchOn) {
            speedUpdateJob = scope.launch {
                while (isActive) {
                    updateNetworkSpeed()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        } else {
            scope.launch {
                updateNetworkSpeed()
            }
        }
    }

    private suspend fun updateNetworkSpeed() {
        val currentTime = System.currentTimeMillis()
        val totalBytes = getTotalBytes()

        var speed = 0L
        if (lastTime > 0 && lastTotalBytes > 0 &&
            totalBytes > lastTotalBytes && currentTime > lastTime
        ) {
            speed = ((totalBytes - lastTotalBytes) * 1000) / (currentTime - lastTime)
        }

        val iconState = NetworkSpeedIconState().apply {
            setVisible(shouldShow(speed))
            setSpeedText(speed)
            setSlot(SLOT_NETWORK_SPEED)
        }

        withContext(mainDispatcher) {
            _iconState.value = iconState.copy()
            iconControllerEx.setNetworkSpeedIcon(SLOT_NETWORK_SPEED, iconState)
            if (networkVisibility != iconState.isVisible()) {
                networkVisibility = iconState.isVisible()
            }
        }

        lastTime = currentTime
        lastTotalBytes = totalBytes
    }

    private fun getTotalBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val traffic = if (rx >= 0 && tx >= 0) rx + tx else 0L
        return if (traffic > 0) traffic else readIfaceBytes()
    }

    private fun readIfaceBytes(): Long {
        return try {
            val sysNet = File("/sys/class/net")
            val ifaces = sysNet.listFiles() ?: return 0L
            var total = 0L
            for (iface in ifaces) {
                if (iface.name == "lo") continue
                val rx = File(iface, "statistics/rx_bytes").readText().trim().toLongOrNull() ?: 0L
                val tx = File(iface, "statistics/tx_bytes").readText().trim().toLongOrNull() ?: 0L
                total += rx + tx
            }
            total
        } catch (_: Exception) {
            0L
        }
    }

    private fun shouldShow(speed: Long): Boolean {
        if (!isConnected || !isSwitchOn) return false
        if (thresholdBytes <= 0L) return true
        return speed > thresholdBytes
    }

    private fun reset() {
        lastTime = 0
        lastTotalBytes = 0
    }

    fun isSwitchOn(): Boolean = isSwitchOn

    private data class IndicatorConfig(val enabled: Boolean, val thresholdKb: Int)

    companion object {
        const val SLOT_NETWORK_SPEED = "network_speed"
        const val ICON_HIDE_LIST = "icon_blacklist"
        const val REFRESH_INTERVAL_MS = 1000L
        const val DEFAULT_THRESHOLD_KB = 1
        private const val KB = 1024L
        private const val PREFS_NAME = "network_speed"
        private const val MIGRATION_PREF = "unhidden"
    }
}
