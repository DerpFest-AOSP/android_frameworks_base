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
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.StatusBarIconControllerImplEx
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
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
            secureSettings
                .observerFlow(ICON_HIDE_LIST)
                .onStart { emit(Unit) }
                .map { readSwitchState() }
                .distinctUntilChanged()
                .flowOn(bgDispatcher)
                .collect { enabled ->
                    isSwitchOn = enabled
                    reset()
                    restartSpeedUpdates()
                }
        }

        keyguardUpdateMonitor.registerCallback(
            object : KeyguardUpdateMonitorCallback() {
                override fun onUserSwitchComplete(newUserId: Int) {
                    isSwitchOn = readSwitchState()
                    reset()
                    restartSpeedUpdates()
                }
            }
        )
    }

    /**
     * Older builds hid this slot by default. Opening any other status-bar tuner switch then
     * persisted that hide list, so later removing it from config would not unhide existing
     * devices. Strip it once; the tuner can hide it again afterwards.
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

    private fun readSwitchState(): Boolean {
        val iconHideList = secureSettings.getString(ICON_HIDE_LIST)
        val hideList = StatusBarIconController.getIconHideList(context, iconHideList)
        return !hideList.contains(SLOT_NETWORK_SPEED)
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
            setVisible(isConnected && isSwitchOn)
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

    private fun reset() {
        lastTime = 0
        lastTotalBytes = 0
    }

    fun isSwitchOn(): Boolean = isSwitchOn

    companion object {
        const val SLOT_NETWORK_SPEED = "network_speed"
        const val ICON_HIDE_LIST = "icon_blacklist"
        const val REFRESH_INTERVAL_MS = 1000L
        private const val PREFS_NAME = "network_speed"
        private const val MIGRATION_PREF = "unhidden"
    }
}
