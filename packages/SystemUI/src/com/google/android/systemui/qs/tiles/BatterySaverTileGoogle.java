package com.google.android.systemui.qs.tiles;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.FlipendoUtils;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tiles.BatterySaverTile;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/**
 * Google variant of {@link BatterySaverTile} that reflects the state of Flipendo (Extreme Battery
 * Saver) in the secondary label.
 */
public class BatterySaverTileGoogle extends BatterySaverTile {

    private final UserTracker mUserTracker;
    private boolean mExtremeEnabled;
    private boolean mExtremeAggressive;

    @Inject
    public BatterySaverTileGoogle(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BatteryController batteryController,
            SecureSettings secureSettings,
            UserTracker userTracker) {
        super(
                host,
                uiEventLogger,
                backgroundLooper,
                mainHandler,
                falsingManager,
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                batteryController,
                secureSettings);
        mUserTracker = userTracker;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.state =
                mPluggedIn
                        ? Tile.STATE_UNAVAILABLE
                        : mPowerSave ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon =
                maybeLoadResourceIcon(
                        mPowerSave
                                ? R.drawable.qs_battery_saver_icon_on
                                : R.drawable.qs_battery_saver_icon_off);
        state.label = mContext.getString(R.string.battery_detail_switch_title);
        state.secondaryLabel = "";
        state.contentDescription = state.label;
        state.value = mPowerSave;
        state.expandedAccessibilityClassName = Switch.class.getName();

        if (state.state == Tile.STATE_ACTIVE) {
            boolean aggressive = true;
            if (!mExtremeEnabled) {
                aggressive =
                        FlipendoUtils.isFlipendoAggressive(
                                mUserTracker.getUserContext().getContentResolver());
            }
            mExtremeAggressive = aggressive;
            state.secondaryLabel =
                    mContext.getString(
                            aggressive
                                    ? R.string.extreme_battery_saver_text
                                    : R.string.standard_battery_saver_text);
        }
        state.stateDescription = state.secondaryLabel;
    }

    @Override
    public void onExtremeBatterySaverChanged(boolean isExtreme) {
        mExtremeEnabled = isExtreme;
        if (!isExtreme || mExtremeAggressive) {
            return;
        }
        refreshState(null);
    }
}
