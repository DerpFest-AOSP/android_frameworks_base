/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.dagger

import com.android.systemui.CoreStartable
import com.android.systemui.hotspot.HotspotDataLimitController
import com.android.systemui.statusbar.policy.NetworkSpeedController
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** DerpFest-specific [CoreStartable] bindings. */
@Module
abstract class DerpStartableModule {
    @Binds
    @IntoMap
    @ClassKey(NetworkSpeedController::class)
    abstract fun bindNetworkSpeedController(impl: NetworkSpeedController): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(HotspotDataLimitController::class)
    abstract fun bindHotspotDataLimitController(impl: HotspotDataLimitController): CoreStartable
}
