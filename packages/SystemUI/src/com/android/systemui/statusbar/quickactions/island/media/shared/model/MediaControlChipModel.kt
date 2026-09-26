/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.quickactions.island.media.shared.model

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.media.controls.shared.model.MediaAction

/** One word inside a lyric line, timed against playback. */
data class LyricWord(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
)

/** A lyric line. [words] is empty when only the line start time is known. */
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
)

/** Model used to display and control media from the status bar island. */
data class MediaControlChipModel(
    val appIcon: Icon?,
    val artworkIcon: Icon?,
    val appName: String?,
    val artistName: CharSequence?,
    val songName: CharSequence?,
    val playOrPause: MediaAction?,
    val nextAction: MediaAction?,
    val previousAction: MediaAction?,
    val openApp: (() -> Unit)?,
    val seekTo: ((Long) -> Unit)?,
    val durationMs: Long,
    val positionMs: Long,
    val canBeScrubbed: Boolean,
    val isPlaying: Boolean,
    val packageName: String? = null,
    val lyrics: String? = null,
    val syncedLyrics: String? = null,
    val timedLyrics: List<LyricLine> = emptyList(),
    val isDynamicIslandLyricsEnabled: Boolean = false,
    val customAction0: MediaAction? = null,
    val customAction1: MediaAction? = null,
)
