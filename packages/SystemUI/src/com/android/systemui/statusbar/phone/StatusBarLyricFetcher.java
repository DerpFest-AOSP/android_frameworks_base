/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a track to line lyrics using the same sources and preference order as the status-bar
 * lyric view.
 */
public final class StatusBarLyricFetcher {
    public static final class TimedWord {
        public final long beginMs;
        public final long endMs;
        public final String text;

        public TimedWord(long beginMs, long endMs, String text) {
            this.beginMs = beginMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    public static final class TimedLine {
        public final long timestampMs;
        public final String text;
        public final List<TimedWord> words;

        public TimedLine(long timestampMs, String text, List<TimedWord> words) {
            this.timestampMs = timestampMs;
            this.text = text;
            this.words = words == null ? Collections.emptyList() : words;
        }
    }

    public static final class Result {
        public final String plainLyrics;
        public final String syncedLyrics;
        public final List<TimedLine> lines;

        public Result(String plainLyrics, String syncedLyrics) {
            this(plainLyrics, syncedLyrics, Collections.emptyList());
        }

        public Result(String plainLyrics, String syncedLyrics, List<TimedLine> lines) {
            this.plainLyrics = plainLyrics;
            this.syncedLyrics = syncedLyrics;
            this.lines = lines == null ? Collections.emptyList() : lines;
        }
    }

    private StatusBarLyricFetcher() {
    }

    /** Fetches lyrics for one track. Returns null when no source has a usable lyric. */
    @Nullable
    public static Result fetch(
            Context context,
            int userId,
            @Nullable String packageName,
            @Nullable String mediaId,
            @Nullable String title,
            @Nullable String artist,
            @Nullable String album,
            long durationMs) {
        if (context == null || TextUtils.isEmpty(title)) {
            return null;
        }
        boolean wordTimingEnabled = Settings.Secure.getIntForUser(
                context.getContentResolver(),
                Settings.Secure.STATUS_BAR_LYRIC_WORD_TIMING,
                1,
                userId) != 0;
        String sourceSetting = Settings.Secure.getStringForUser(
                context.getContentResolver(),
                Settings.Secure.STATUS_BAR_LYRIC_SOURCES,
                userId);
        LyricSource.Track track = new LyricSource.Track(
                packageName, mediaId, title, artist, album, durationMs);
        try {
            LyricSource.Lyrics lyrics = null;
            List<LyricSource> sources = LyricSourceFactory.create(sourceSetting);
            if (wordTimingEnabled) {
                for (LyricSource source : sources) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    LyricSource.Lyrics enhancedLyrics = source.fetchEnhanced(track);
                    if (enhancedLyrics != null && enhancedLyrics.hasWordTiming()) {
                        lyrics = enhancedLyrics;
                        break;
                    }
                }
            }
            if (lyrics == null) {
                for (LyricSource source : sources) {
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    lyrics = source.fetch(track);
                    if (lyrics != null) {
                        break;
                    }
                }
            }
            return toResult(lyrics);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    @Nullable
    private static Result toResult(@Nullable LyricSource.Lyrics lyrics) {
        if (lyrics == null) {
            return null;
        }
        StringBuilder plain = new StringBuilder();
        StringBuilder synced = new StringBuilder();
        List<TimedLine> lines = new ArrayList<>();
        for (LyricSource.Cue cue : lyrics.getCues()) {
            if (cue == null || TextUtils.isEmpty(cue.text)) {
                continue;
            }
            if (plain.length() > 0) {
                plain.append('\n');
            }
            plain.append(cue.text);
            if (synced.length() > 0) {
                synced.append('\n');
            }
            synced.append(formatLrcTimestamp(cue.timestampMs)).append(cue.text);
            lines.add(new TimedLine(cue.timestampMs, cue.text, timedWords(cue)));
        }
        if (plain.length() == 0) {
            return null;
        }
        return new Result(plain.toString(), synced.toString(), lines);
    }

    private static List<TimedWord> timedWords(LyricSource.Cue cue) {
        if (cue.words == null || cue.words.isEmpty()) {
            return Collections.emptyList();
        }
        List<TimedWord> words = new ArrayList<>();
        for (LyricSource.Word word : cue.words) {
            if (word == null || TextUtils.isEmpty(word.text)) {
                continue;
            }
            words.add(new TimedWord(word.beginMs, word.endMs, word.text));
        }
        return words;
    }

    private static String formatLrcTimestamp(long timestampMs) {
        long clamped = Math.max(0, timestampMs);
        long minutes = clamped / 60000L;
        long seconds = (clamped % 60000L) / 1000L;
        long centiseconds = (clamped % 1000L) / 10L;
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centiseconds);
    }
}
