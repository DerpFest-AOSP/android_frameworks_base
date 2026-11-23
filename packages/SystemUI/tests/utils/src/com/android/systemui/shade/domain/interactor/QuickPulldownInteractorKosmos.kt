/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.domain.interactor

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.shade.shared.model.QuickPulldown
import kotlinx.coroutines.flow.MutableStateFlow

class FakeQuickPulldownInteractor(initial: QuickPulldown = QuickPulldown.OFF) :
    QuickPulldownInteractor {
    override val quickPulldown = MutableStateFlow(initial)
}

val Kosmos.fakeQuickPulldownInteractor by Kosmos.Fixture { FakeQuickPulldownInteractor() }

var Kosmos.quickPulldownInteractor: QuickPulldownInteractor by
    Kosmos.Fixture { fakeQuickPulldownInteractor }
