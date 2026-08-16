/*
 * Copyright (C) 2026 Project ASCP
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

package com.android.systemui.hotspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.ContentObserver;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.res.R;

import java.util.Set;

import javax.inject.Inject;

/**
 * Enforces the one-time hotspot data limit from Settings.
 *
 * <ul>
 *   <li>{@code Settings.Global hotspot_data_limit_enabled} (0/1)
 *   <li>{@code Settings.Global hotspot_data_limit_bytes}
 *   <li>{@code Settings.Global hotspot_data_limit_action}
 *       (0 = turn the hotspot off and notify, 1 = notify only)
 * </ul>
 *
 * Usage is read from the Wi-Fi tether interfaces. That is the traffic the hotspot itself
 * carries, rather than every Wi-Fi transfer on the phone.
 */
@SysUISingleton
public class HotspotDataLimitController implements CoreStartable {

    private static final String TAG = "HotspotDataLimit";
    private static final long CHECK_INTERVAL_MS = 15_000;
    private static final String CHANNEL_ID = "hotspot_data_limit";
    private static final int NOTIF_ID = 7001;

    private static final String KEY_ENABLED = "hotspot_data_limit_enabled";
    private static final String KEY_LIMIT_BYTES = "hotspot_data_limit_bytes";
    private static final String KEY_ACTION = "hotspot_data_limit_action";
    private static final int ACTION_TURN_OFF_NOTIFY = 0;
    private static final long DEFAULT_LIMIT_BYTES = 50L * 1024L * 1024L;

    private final Context mContext;
    private final Handler mHandler;
    private final NotificationManager mNotificationManager;
    private final TetheringManager mTetheringManager;
    private final WifiManager mWifiManager;

    private boolean mApEnabled;
    private boolean mHotspotActive;
    private boolean mLimitNotifiedThisSession;
    private long mCarriedBytes;
    private final ArraySet<String> mWifiIfaces = new ArraySet<>();
    private final ArrayMap<String, Long> mBaselineBytes = new ArrayMap<>();
    private final ArrayMap<String, Long> mLastBytes = new ArrayMap<>();

    private final Runnable mCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkDataUsage();
            if (mHotspotActive) {
                mHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    };

    private final ContentObserver mSettingsObserver;

    private final TetheringManager.TetheringEventCallback mTetheringCallback =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
                    mWifiIfaces.clear();
                    for (TetheringInterface tetherIface : interfaces) {
                        if (tetherIface.getType() == TetheringManager.TETHERING_WIFI) {
                            mWifiIfaces.add(tetherIface.getInterface());
                        }
                    }
                }
            };

    private final WifiManager.SoftApCallback mSoftApCallback = new WifiManager.SoftApCallback() {
        @Override
        public void onStateChanged(int state, int failureReason) {
            if (state == WifiManager.WIFI_AP_STATE_ENABLED) {
                mApEnabled = true;
                onHotspotEnabled();
            } else if (state == WifiManager.WIFI_AP_STATE_DISABLED
                    || state == WifiManager.WIFI_AP_STATE_FAILED) {
                mApEnabled = false;
                onHotspotDisabled();
            }
        }
    };

    @Inject
    public HotspotDataLimitController(Context context, @Background Handler bgHandler) {
        mContext = context;
        mHandler = bgHandler;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mTetheringManager = context.getSystemService(TetheringManager.class);
        mWifiManager = context.getSystemService(WifiManager.class);
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                if (!mApEnabled) {
                    return;
                }
                if (isFeatureEnabled()) {
                    onHotspotEnabled();
                } else {
                    onHotspotDisabled();
                }
            }
        };
    }

    @Override
    public void start() {
        createNotificationChannel();
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(KEY_ENABLED), false, mSettingsObserver);
        if (mTetheringManager != null) {
            mTetheringManager.registerTetheringEventCallback(
                    new HandlerExecutor(mHandler), mTetheringCallback);
        }
        if (mWifiManager != null) {
            mWifiManager.registerSoftApCallback(new HandlerExecutor(mHandler), mSoftApCallback);
        }
    }

    private void onHotspotEnabled() {
        if (!isFeatureEnabled() || mHotspotActive) {
            return;
        }
        mHotspotActive = true;
        mLimitNotifiedThisSession = false;
        mCarriedBytes = 0;
        mBaselineBytes.clear();
        mLastBytes.clear();
        mHandler.removeCallbacks(mCheckRunnable);
        mHandler.post(mCheckRunnable);
        Log.d(TAG, "Monitoring hotspot data limit");
    }

    private void onHotspotDisabled() {
        mHotspotActive = false;
        mLimitNotifiedThisSession = false;
        mCarriedBytes = 0;
        mBaselineBytes.clear();
        mLastBytes.clear();
        mHandler.removeCallbacks(mCheckRunnable);
    }

    private void checkDataUsage() {
        if (!isFeatureEnabled() || mLimitNotifiedThisSession) {
            return;
        }
        final long limitBytes = getLimitBytes();
        if (limitBytes <= 0) {
            return;
        }
        final long used = getSessionUsage();
        if (used < 0 || used < limitBytes) {
            return;
        }

        final int action = Settings.Global.getInt(
                mContext.getContentResolver(), KEY_ACTION, ACTION_TURN_OFF_NOTIFY);
        mLimitNotifiedThisSession = true;
        if (action == ACTION_TURN_OFF_NOTIFY) {
            disableHotspot();
        }
        showLimitReachedNotification(action == ACTION_TURN_OFF_NOTIFY);
        Log.d(TAG, "Hotspot data limit reached, used=" + used + " limit=" + limitBytes);
    }

    /**
     * Bytes transferred on the current Wi-Fi hotspot interfaces since this session started.
     * Returns -1 until an interface counter is available.
     */
    private long getSessionUsage() {
        final ArraySet<String> live = new ArraySet<>(mWifiIfaces);
        for (int i = mBaselineBytes.size() - 1; i >= 0; i--) {
            final String iface = mBaselineBytes.keyAt(i);
            if (live.contains(iface)) {
                continue;
            }
            final long baseline = mBaselineBytes.valueAt(i);
            final Long last = mLastBytes.get(iface);
            if (last != null && last >= baseline) {
                mCarriedBytes += last - baseline;
            }
            mBaselineBytes.removeAt(i);
            mLastBytes.remove(iface);
        }

        long used = mCarriedBytes;
        boolean measured = mCarriedBytes > 0;
        for (int i = 0; i < live.size(); i++) {
            final String iface = live.valueAt(i);
            final long now = readIfaceBytes(iface);
            if (now < 0) {
                continue;
            }
            measured = true;
            final int index = mBaselineBytes.indexOfKey(iface);
            if (index < 0) {
                mBaselineBytes.put(iface, now);
                mLastBytes.put(iface, now);
                continue;
            }
            final long baseline = mBaselineBytes.valueAt(index);
            if (now < baseline) {
                final Long last = mLastBytes.get(iface);
                if (last != null && last >= baseline) {
                    mCarriedBytes += last - baseline;
                    used += last - baseline;
                }
                mBaselineBytes.put(iface, now);
                mLastBytes.put(iface, now);
                continue;
            }
            mLastBytes.put(iface, now);
            used += now - baseline;
        }
        return measured ? used : -1;
    }

    private static long readIfaceBytes(String iface) {
        final long rx = TrafficStats.getRxBytes(iface);
        final long tx = TrafficStats.getTxBytes(iface);
        if (rx < 0 || tx < 0) {
            return -1;
        }
        return rx + tx;
    }

    private void disableHotspot() {
        if (mTetheringManager == null) {
            return;
        }
        try {
            mTetheringManager.stopTethering(TetheringManager.TETHERING_WIFI);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to disable hotspot", e);
        }
    }

    private void createNotificationChannel() {
        if (mNotificationManager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                mContext.getString(R.string.hotspot_data_limit_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showLimitReachedNotification(boolean hotspotWasTurnedOff) {
        if (mNotificationManager == null) {
            return;
        }
        final String text = mContext.getString(hotspotWasTurnedOff
                ? R.string.hotspot_data_limit_notification_text_off
                : R.string.hotspot_data_limit_notification_text);
        final Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.stat_sys_hotspot)
                .setContentTitle(mContext.getString(R.string.hotspot_data_limit_notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        mNotificationManager.notify(NOTIF_ID, notification);
    }

    private boolean isFeatureEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(), KEY_ENABLED, 0) == 1;
    }

    private long getLimitBytes() {
        return Settings.Global.getLong(
                mContext.getContentResolver(), KEY_LIMIT_BYTES, DEFAULT_LIMIT_BYTES);
    }
}
