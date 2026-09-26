/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.lockscreen.elementproviders

import android.content.testableContext
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.AxDynamicBarKeyguardExpansion
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.keyguard.ui.composable.elements.LockscreenRootElementProvider
import com.android.systemui.keyguard.ui.viewmodel.aodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.keyguardClockViewModelWithImpl
import com.android.systemui.keyguard.ui.viewmodel.lockscreenRootViewModelFactory
import com.android.systemui.kosmos.Kosmos
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

val Kosmos.lockscreenRootElementProvider by
    Kosmos.Fixture {
        LockscreenRootElementProvider(
            context = testableContext,
            viewModelFactory = lockscreenRootViewModelFactory,
            aodBurnInViewModel = aodBurnInViewModel,
            keyguardClockViewModel = keyguardClockViewModelWithImpl,
            axDynamicBarChipViewModel = hiddenAxDynamicBarChipViewModel(),
        )
    }

/** Lockscreen scene tests compose the dynamic bar. Keep it hidden so they do not need the real pipeline. */
private fun hiddenAxDynamicBarChipViewModel(): AxDynamicBarChipViewModel {
    val settings = mock<AxDynamicBarSettings>()
    val interactor =
        mock<AxDynamicBarInteractor>().apply {
            whenever(this.settings).thenReturn(settings)
            whenever(this.mediaControlChipModel).thenReturn(MutableStateFlow(null))
        }
    val expansion = mock<AxDynamicBarKeyguardExpansion>()
    return mock<AxDynamicBarChipViewModel>().apply {
        whenever(this.interactor).thenReturn(interactor)
        whenever(this.keyguardExpansion).thenReturn(expansion)
        whenever(this.chipState).thenReturn(MutableStateFlow(null))
        whenever(this.isOnKeyguard).thenReturn(MutableStateFlow(false))
        whenever(this.isEnabled).thenReturn(MutableStateFlow(false))
        whenever(this.isKeyguardEnabled).thenReturn(MutableStateFlow(false))
        whenever(this.isLockscreenMediaEnabled).thenReturn(MutableStateFlow(false))
        whenever(this.isLockscreenMediaLyricsEnabled).thenReturn(MutableStateFlow(false))
        whenever(this.keyguardBatteryChipMode).thenReturn(MutableStateFlow(0))
        whenever(this.keyguardBatteryInfo)
            .thenReturn(MutableStateFlow(KeyguardBatteryInfo(0, false, false, false, null)))
        whenever(this.isKeyguardExpanded).thenReturn(MutableStateFlow(false))
        whenever(this.batteryString).thenReturn(MutableStateFlow(""))
        whenever(this.isDozing).thenReturn(MutableStateFlow(false))
    }
}
