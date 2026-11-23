/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.domain.interactor

import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shade.shared.model.QuickPulldown
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import lineageos.providers.LineageSettings

/** Reads which edge of the status bar opens Quick Settings with one finger. */
interface QuickPulldownInteractor {
    val quickPulldown: StateFlow<QuickPulldown>
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class QuickPulldownInteractorImpl
@Inject
constructor(
    @Application private val context: Context,
    userRepository: UserRepository,
    @Background backgroundDispatcher: CoroutineDispatcher,
    @Background backgroundScope: CoroutineScope,
) : QuickPulldownInteractor {

    override val quickPulldown: StateFlow<QuickPulldown> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                conflatedCallbackFlow {
                    val observer =
                        object : ContentObserver(null) {
                            override fun onChange(selfChange: Boolean) {
                                trySend(read(user.id))
                            }
                        }
                    trySend(read(user.id))
                    context.contentResolver.registerContentObserver(
                        LineageSettings.System.getUriFor(SETTING),
                        false,
                        observer,
                        UserHandle.USER_ALL,
                    )
                    awaitClose { context.contentResolver.unregisterContentObserver(observer) }
                }
            }
            .flowOn(backgroundDispatcher)
            .stateIn(backgroundScope, SharingStarted.Eagerly, QuickPulldown.OFF)

    private fun read(userId: Int): QuickPulldown {
        return try {
            QuickPulldown.fromInt(
                LineageSettings.System.getIntForUser(context.contentResolver, SETTING, 0, userId)
            )
        } catch (_: Throwable) {
            QuickPulldown.OFF
        }
    }

    private companion object {
        const val SETTING = LineageSettings.System.STATUS_BAR_QUICK_QS_PULLDOWN
    }
}
