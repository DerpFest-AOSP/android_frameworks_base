/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.quickactions.island.media.ui.compose

import android.icu.text.Bidi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.statusbar.quickactions.island.media.shared.model.LyricLine
import com.android.systemui.statusbar.quickactions.island.media.shared.model.LyricWord
import com.android.systemui.statusbar.quickactions.island.media.shared.model.MediaControlChipModel

private val timestampRegex = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")
private const val WORD_SWEEP_FRAME_MS = 16L
private const val UNSUNG_ALPHA = 0.45f

@Composable
fun LyricsCard(
    model: MediaControlChipModel,
    modifier: Modifier = Modifier,
) {
    val syncedLyrics = model.syncedLyrics
    val plainLyrics = model.lyrics

    val lyricLines = remember(model.timedLyrics, syncedLyrics) {
        when {
            model.timedLyrics.isNotEmpty() -> model.timedLyrics
            syncedLyrics.isNullOrBlank() -> emptyList()
            else -> parseLrc(syncedLyrics)
        }
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier.fillMaxSize()) {
        if (lyricLines.isNotEmpty()) {
            val hasWordTiming = remember(lyricLines) { lyricLines.any { it.words.isNotEmpty() } }
            val currentPosition = rememberLyricPositionMs(
                positionMs = model.positionMs,
                isPlaying = model.isPlaying,
                frameMs = if (hasWordTiming) WORD_SWEEP_FRAME_MS else 200L,
            )

            val activeIndex = remember(lyricLines, currentPosition) {
                lyricLines.indexOfLast { currentPosition >= it.timestampMs }
            }

            val lazyListState = rememberLazyListState()

            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0 && activeIndex < lyricLines.size) {
                    lazyListState.animateScrollToItem(activeIndex)
                }
            }

            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(vertical = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                itemsIndexed(lyricLines) { index, line ->
                    val isActive = index == activeIndex
                    AnimatedLyricsLine(
                        text = line.text,
                        fontSize = 16.sp,
                        alpha = if (isActive) 1f else 0.4f,
                        scale = if (isActive) 1.04f else 0.96f,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = LocalContentColor.current,
                        words = if (isActive) line.words else emptyList(),
                        lineStartMs = line.timestampMs,
                        positionMs = currentPosition,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                        animationMillis = 250,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        } else if (!plainLyrics.isNullOrBlank()) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                item {
                    Text(
                        text = plainLyrics,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        }
    }
}

@Composable
fun LockscreenLyricsView(
    model: MediaControlChipModel,
    modifier: Modifier = Modifier,
) {
    val syncedLyrics = model.syncedLyrics
    val lyricLines = remember(model.timedLyrics, syncedLyrics) {
        when {
            model.timedLyrics.isNotEmpty() -> model.timedLyrics
            syncedLyrics.isNullOrBlank() -> emptyList()
            else -> parseLrc(syncedLyrics)
        }
    }

    if (lyricLines.isEmpty()) return

    val hasWordTiming = remember(lyricLines) { lyricLines.any { it.words.isNotEmpty() } }
    val currentPosition = rememberLyricPositionMs(
        positionMs = model.positionMs,
        isPlaying = model.isPlaying,
        frameMs = if (hasWordTiming) WORD_SWEEP_FRAME_MS else 100L,
    )

    val activeIndex = remember(lyricLines, currentPosition) {
        lyricLines.indexOfLast { currentPosition >= it.timestampMs }
    }

    if (activeIndex < 0) return

    val prevLine = lyricLines.getOrNull(activeIndex - 1)
    val currentLine = lyricLines.getOrNull(activeIndex)
    val nextLine = lyricLines.getOrNull(activeIndex + 1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Line 1
        AnimatedLyricsLine(
            text = prevLine?.text.orEmpty(),
            fontSize = 13.sp,
            alpha = 0.45f,
            scale = 0.9f,
            fontWeight = FontWeight.Normal,
        )

        // Line 2
        AnimatedLyricsLine(
            text = currentLine?.text.orEmpty(),
            fontSize = 16.sp,
            alpha = 1.0f,
            scale = 1.05f,
            fontWeight = FontWeight.SemiBold,
            words = currentLine?.words.orEmpty(),
            lineStartMs = currentLine?.timestampMs ?: 0L,
            positionMs = currentPosition,
        )

        // Line 3
        AnimatedLyricsLine(
            text = nextLine?.text.orEmpty(),
            fontSize = 13.sp,
            alpha = 0.45f,
            scale = 0.9f,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun rememberLyricPositionMs(
    positionMs: Long,
    isPlaying: Boolean,
    frameMs: Long,
): Long {
    var currentPosition by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying, frameMs) {
        if (isPlaying) {
            val baseRealtime = android.os.SystemClock.elapsedRealtime()
            val basePos = positionMs
            while (isActive) {
                currentPosition = basePos + (android.os.SystemClock.elapsedRealtime() - baseRealtime)
                delay(frameMs)
            }
        } else {
            currentPosition = positionMs
        }
    }
    return currentPosition
}

@Composable
private fun AnimatedLyricsLine(
    text: String,
    fontSize: TextUnit,
    alpha: Float,
    scale: Float,
    fontWeight: FontWeight,
    color: Color = Color.White,
    words: List<LyricWord> = emptyList(),
    lineStartMs: Long = 0L,
    positionMs: Long = 0L,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    animationMillis: Int = 350,
    modifier: Modifier = Modifier,
) {
    val animAlpha by animateFloatAsState(
        targetValue = if (text.isEmpty()) 0f else alpha,
        animationSpec = tween(animationMillis),
        label = "lyric_line_alpha",
    )
    val animScale by animateFloatAsState(
        targetValue = if (text.isEmpty()) 0.8f else scale,
        animationSpec = tween(animationMillis),
        label = "lyric_line_scale",
    )
    val lineModifier = modifier
        .fillMaxWidth()
        .graphicsLayer {
            this.alpha = animAlpha
            scaleX = animScale
            scaleY = animScale
        }
    if (words.isEmpty()) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = overflow,
            modifier = lineModifier,
        )
        return
    }

    val textStyle = LocalTextStyle.current.merge(
        TextStyle(fontSize = fontSize, fontWeight = fontWeight, textAlign = TextAlign.Center)
    )
    val alignedWords = remember(words, lineStartMs) { alignWordTimes(words, lineStartMs) }
    val spans = remember(text, alignedWords) { wordSpans(text, alignedWords) }
    val cursor = highlightCursor(spans, positionMs)
    val rtl = remember(text) { Bidi.getBaseDirection(text) == Bidi.RTL }
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Box(lineModifier) {
        Text(
            text = text,
            color = color.copy(alpha = color.alpha * UNSUNG_ALPHA),
            style = textStyle,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { textLayout = it },
            modifier = Modifier.fillMaxWidth(),
        )
        if (cursor.exclusiveEnd > 0 || cursor.partialFraction > 0f) {
            Text(
                text = text,
                color = color,
                style = textStyle,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val layout = textLayout ?: return@drawWithContent
                        val path = highlightPath(layout, cursor, rtl)
                        if (!path.isEmpty) {
                            clipPath(path) { this@drawWithContent.drawContent() }
                        }
                    },
            )
        }
    }
}

private data class WordSpan(val start: Int, val end: Int, val beginMs: Long, val endMs: Long)

private data class HighlightCursor(val exclusiveEnd: Int, val partialFraction: Float)

/**
 * Word times that already begin with the line are song times. Times that start well before the
 * line are offsets from that line: the opening line begins at 0, so the mistake only shows up
 * afterwards.
 */
private fun alignWordTimes(words: List<LyricWord>, lineStartMs: Long): List<LyricWord> {
    if (words.isEmpty() || lineStartMs <= 0L) return words
    val firstBegin = words.minOf { it.beginMs }
    if (firstBegin + 1_000L >= lineStartMs) return words
    return words.map { word ->
        word.copy(beginMs = word.beginMs + lineStartMs, endMs = word.endMs + lineStartMs)
    }
}

private fun wordSpans(text: String, words: List<LyricWord>): List<WordSpan> {
    val spans = ArrayList<WordSpan>(words.size)
    var textOffset = 0
    for (word in words) {
        if (word.text.isEmpty()) continue
        val wordEnd = minOf(text.length, textOffset + word.text.length)
        if (wordEnd <= textOffset) continue
        spans.add(WordSpan(textOffset, wordEnd, word.beginMs, word.endMs))
        textOffset = wordEnd
        if (textOffset >= text.length) break
    }
    return spans
}

private fun highlightCursor(words: List<WordSpan>, positionMs: Long): HighlightCursor {
    var exclusiveEnd = 0
    for (word in words) {
        val length = word.end - word.start
        if (length <= 0) continue
        if (positionMs >= word.endMs || (word.endMs <= word.beginMs && positionMs >= word.beginMs)) {
            exclusiveEnd = word.end
        } else if (positionMs > word.beginMs && word.endMs > word.beginMs) {
            val progress =
                ((positionMs - word.beginMs).toFloat() / (word.endMs - word.beginMs)).coerceIn(0f, 1f)
            val exact = word.start + progress * length
            val full = exact.toInt().coerceIn(word.start, word.end)
            val fraction = (exact - full).coerceIn(0f, 1f)
            return HighlightCursor(full, if (full < word.end) fraction else 0f)
        } else {
            break
        }
    }
    return HighlightCursor(exclusiveEnd, 0f)
}

private fun highlightPath(layout: TextLayoutResult, cursor: HighlightCursor, rtl: Boolean): Path {
    val path = Path()
    val length = layout.layoutInput.text.length
    val end = cursor.exclusiveEnd.coerceIn(0, length)
    if (end > 0) {
        path.addPath(layout.getPathForRange(0, end))
    }
    if (cursor.partialFraction > 0f && end < length) {
        val box = layout.getBoundingBox(end)
        val slice = box.width * cursor.partialFraction
        if (slice > 0f) {
            path.addRect(
                if (rtl) {
                    Rect(box.right - slice, box.top, box.right, box.bottom)
                } else {
                    Rect(box.left, box.top, box.left + slice, box.bottom)
                }
            )
        }
    }
    return path
}

private fun parseLrc(lrcText: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    
    lrcText.split("\n").forEach { lineStr ->
        val trimmedLine = lineStr.trim()
        if (trimmedLine.isBlank()) return@forEach
        
        val timestamps = mutableListOf<Long>()
        var currentIndex = 0
        while (currentIndex < trimmedLine.length) {
            val match = timestampRegex.find(trimmedLine, currentIndex)
            if (match == null || match.range.first != currentIndex) {
                break
            }
            
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val fraction = match.groupValues[3]
            val fracMs = when (fraction.length) {
                1 -> fraction.toLong() * 100L
                2 -> fraction.toLong() * 10L
                3 -> fraction.toLong()
                else -> 0L
            }
            val timestampMs = (min * 60 + sec) * 1000L + fracMs
            timestamps.add(timestampMs)
            
            currentIndex = match.range.last + 1
        }
        
        val text = trimmedLine.substring(currentIndex).trim()
        if (text.isNotEmpty() || lines.isNotEmpty()) {
            timestamps.forEach { ts ->
                lines.add(LyricLine(ts, text))
            }
        }
    }
    return lines.sortedBy { it.timestampMs }
}
