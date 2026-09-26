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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.statusbar.quickactions.island.media.shared.model.LyricLine
import com.android.systemui.statusbar.quickactions.island.media.shared.model.LyricWord
import com.android.systemui.statusbar.quickactions.island.media.shared.model.MediaControlChipModel

private val PopupShape = RoundedCornerShape(34.dp)
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

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = PopupShape,
        shadowElevation = 12.dp,
        modifier = modifier.widthIn(min = 320.dp, max = 400.dp).height(200.dp),
    ) {
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

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(fontSize = fontSize, fontWeight = fontWeight, textAlign = TextAlign.Center)
    )
    val spans = remember(text, words, textStyle, density) {
        measureWords(text, words, textStyle, measurer)
    }
    val highlighted = highlightedWidth(spans, positionMs)
    val rtl = remember(text) { Bidi.getBaseDirection(text) == Bidi.RTL }
    var lineLeft by remember(text) { mutableFloatStateOf(0f) }
    var lineRight by remember(text) { mutableFloatStateOf(0f) }
    var lineCount by remember(text) { mutableIntStateOf(0) }
    Box(lineModifier) {
        Text(
            text = text,
            color = color.copy(alpha = color.alpha * UNSUNG_ALPHA),
            style = textStyle,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout ->
                lineCount = layout.lineCount
                if (layout.lineCount > 0) {
                    lineLeft = layout.getLineLeft(0)
                    lineRight = layout.getLineRight(0)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (highlighted > 0f && (lineCount > 1 || lineRight > lineLeft)) {
            Text(
                text = text,
                color = color,
                style = textStyle,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        if (lineCount > 1) {
                            drawContent()
                            return@drawWithContent
                        }
                        val leftEdge = if (rtl) {
                            (lineRight - highlighted).coerceAtLeast(lineLeft)
                        } else {
                            lineLeft
                        }
                        val rightEdge = if (rtl) {
                            lineRight
                        } else {
                            (lineLeft + highlighted).coerceAtMost(lineRight)
                        }
                        if (rightEdge > leftEdge) {
                            clipRect(leftEdge, 0f, rightEdge, size.height) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    },
            )
        }
    }
}

private data class MeasuredWord(val width: Float, val beginMs: Long, val endMs: Long)

private fun measureWords(
    text: String,
    words: List<LyricWord>,
    style: TextStyle,
    measurer: TextMeasurer,
): List<MeasuredWord> {
    val measured = ArrayList<MeasuredWord>(words.size)
    var textOffset = 0
    for (word in words) {
        if (word.text.isEmpty()) continue
        val wordEnd = minOf(text.length, textOffset + word.text.length)
        if (wordEnd <= textOffset) continue
        val wordText = text.substring(textOffset, wordEnd)
        val width = measurer.measure(wordText, style, softWrap = false).size.width.toFloat()
        measured.add(MeasuredWord(width, word.beginMs, word.endMs))
        textOffset = wordEnd
        if (textOffset >= text.length) break
    }
    return measured
}

private fun highlightedWidth(words: List<MeasuredWord>, positionMs: Long): Float {
    var highlighted = 0f
    for (word in words) {
        if (positionMs >= word.endMs ||
            (word.endMs <= word.beginMs && positionMs >= word.beginMs)
        ) {
            highlighted += word.width
        } else if (positionMs > word.beginMs && word.endMs > word.beginMs) {
            val progress = (positionMs - word.beginMs).toFloat() / (word.endMs - word.beginMs).toFloat()
            highlighted += word.width * progress.coerceIn(0f, 1f)
            break
        } else {
            break
        }
    }
    return highlighted
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
