/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the common JSON, LRC, and YRC lyric response formats. */
final class LyricResponseParser {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE_SIZE = 2 * 1024 * 1024;
    private static final long MAX_TRANSLATION_DELTA_MS = 1_000;
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]");
    private static final Pattern OFFSET_PATTERN = Pattern.compile("\\[offset:([+-]?\\d+)\\]");
    private static final Pattern YRC_LINE_PATTERN = Pattern.compile("\\[(\\d+),(\\d+)\\](.*)");
    private static final Pattern YRC_WORD_PATTERN = Pattern.compile(
            "\\((\\d+),(\\d+),\\d+\\)([^\\(]*)");

    private LyricResponseParser() {
    }

    static LyricSource.Lyrics parseLyrics(JSONObject response) {
        JSONObject lrc = response.optJSONObject("lrc");
        JSONObject translatedLrc = response.optJSONObject("tlyric");
        JSONObject enhancedTranslatedLrc = response.optJSONObject("ytlrc");
        JSONObject enhancedLrc = response.optJSONObject("yrc");
        String original = lrc == null ? null : lrc.optString("lyric", null);
        String translated = translatedLrc == null ? null : translatedLrc.optString("lyric", null);
        String enhancedTranslated = enhancedTranslatedLrc == null
                ? null : enhancedTranslatedLrc.optString("lyric", null);
        String enhanced = enhancedLrc == null ? null : enhancedLrc.optString("lyric", null);
        if (TextUtils.isEmpty(original) && TextUtils.isEmpty(translated)
                && TextUtils.isEmpty(enhanced)) {
            return null;
        }

        TreeMap<Long, String> originalLines = parseLrc(original, getOffsetMs(original));
        TreeMap<Long, String> translatedLines = parseTranslatedLines(
                enhancedTranslated, translated);
        TreeMap<Long, YrcLine> yrcLines = parseYrc(enhanced);
        TreeMap<Long, LyricSource.Cue> cues = new TreeMap<>();
        if (!yrcLines.isEmpty()) {
            for (YrcLine line : yrcLines.values()) {
                cues.put(line.beginMs, new LyricSource.Cue(line.beginMs, line.text,
                        findClosestLine(translatedLines, line.beginMs), line.words));
            }
            return cues.isEmpty() ? null : new LyricSource.Lyrics(cues);
        }
        if (originalLines.isEmpty()) {
            for (Map.Entry<Long, String> entry : translatedLines.entrySet()) {
                cues.put(entry.getKey(), new LyricSource.Cue(
                        entry.getKey(), entry.getValue(), null));
            }
            return cues.isEmpty() ? null : new LyricSource.Lyrics(cues);
        }
        for (Map.Entry<Long, String> entry : originalLines.entrySet()) {
            cues.put(entry.getKey(), new LyricSource.Cue(entry.getKey(), entry.getValue(),
                    findClosestLine(translatedLines, entry.getKey())));
        }
        return cues.isEmpty() ? null : new LyricSource.Lyrics(cues);
    }

    private static TreeMap<Long, String> parseTranslatedLines(String enhancedTranslated,
            String translated) {
        if (!TextUtils.isEmpty(enhancedTranslated)) {
            TreeMap<Long, YrcLine> enhancedLines = parseYrc(enhancedTranslated);
            if (!enhancedLines.isEmpty()) {
                TreeMap<Long, String> lines = new TreeMap<>();
                for (YrcLine line : enhancedLines.values()) {
                    lines.put(line.beginMs, line.text);
                }
                return lines;
            }
            TreeMap<Long, String> enhancedLrc = parseLrc(enhancedTranslated,
                    getOffsetMs(enhancedTranslated));
            if (!enhancedLrc.isEmpty()) {
                return enhancedLrc;
            }
        }
        return parseLrc(translated, getOffsetMs(translated));
    }

    private static TreeMap<Long, YrcLine> parseYrc(String lyric) {
        TreeMap<Long, YrcLine> lines = new TreeMap<>();
        if (TextUtils.isEmpty(lyric)) {
            return lines;
        }
        for (String rawLine : lyric.split("\\r?\\n")) {
            String lineText = rawLine.trim();
            if (lineText.isEmpty() || lineText.startsWith("{")) {
                continue;
            }
            Matcher lineMatcher = YRC_LINE_PATTERN.matcher(lineText);
            if (!lineMatcher.find()) {
                continue;
            }
            long beginMs;
            long durationMs;
            try {
                beginMs = Long.parseLong(lineMatcher.group(1));
                durationMs = Long.parseLong(lineMatcher.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            Matcher wordMatcher = YRC_WORD_PATTERN.matcher(lineMatcher.group(3));
            ArrayList<LyricSource.Word> words = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            while (wordMatcher.find()) {
                String wordText = wordMatcher.group(3);
                if (TextUtils.isEmpty(wordText)) {
                    continue;
                }
                try {
                    long wordBeginMs = Long.parseLong(wordMatcher.group(1));
                    long wordDurationMs = Long.parseLong(wordMatcher.group(2));
                    words.add(new LyricSource.Word(wordBeginMs,
                            wordBeginMs + wordDurationMs, wordText));
                    text.append(wordText);
                } catch (NumberFormatException e) {
                    words.clear();
                    break;
                }
            }
            if (!words.isEmpty() && !TextUtils.isEmpty(text)) {
                words.sort((first, second) -> Long.compare(first.beginMs, second.beginMs));
                StringBuilder sortedText = new StringBuilder();
                for (LyricSource.Word word : words) {
                    sortedText.append(word.text);
                }
                lines.put(beginMs, new YrcLine(beginMs, durationMs, sortedText.toString(), words));
            }
        }
        return lines;
    }

    private static String findClosestLine(TreeMap<Long, String> lines, long timestampMs) {
        Map.Entry<Long, String> floor = lines.floorEntry(timestampMs);
        Map.Entry<Long, String> ceiling = lines.ceilingEntry(timestampMs);
        Map.Entry<Long, String> closest;
        if (floor == null) {
            closest = ceiling;
        } else if (ceiling == null) {
            closest = floor;
        } else {
            closest = timestampMs - floor.getKey() <= ceiling.getKey() - timestampMs
                    ? floor : ceiling;
        }
        return closest != null && Math.abs(closest.getKey() - timestampMs)
                <= MAX_TRANSLATION_DELTA_MS ? closest.getValue() : null;
    }

    private static long getOffsetMs(String lyric) {
        if (lyric == null) {
            return 0;
        }
        Matcher matcher = OFFSET_PATTERN.matcher(lyric);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static TreeMap<Long, String> parseLrc(String lyric, long offsetMs) {
        TreeMap<Long, String> lines = new TreeMap<>();
        if (TextUtils.isEmpty(lyric)) {
            return lines;
        }
        for (String line : lyric.split("\\r?\\n")) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(line);
            TreeMap<Long, Boolean> timestamps = new TreeMap<>();
            while (matcher.find()) {
                long timestampMs = parseTimestampMs(matcher);
                if (timestampMs >= 0) {
                    timestamps.put(Math.max(0, timestampMs + offsetMs), true);
                }
            }
            if (!timestamps.isEmpty()) {
                int textStart = 0;
                Matcher prefixMatcher = TIMESTAMP_PATTERN.matcher(line);
                while (prefixMatcher.find()) {
                    textStart = prefixMatcher.end();
                }
                String lineText = textForCue(line.substring(textStart));
                if (!TextUtils.isEmpty(lineText)) {
                    for (Long timestampMs : timestamps.keySet()) {
                        lines.put(timestampMs, lineText);
                    }
                }
            }
        }
        return lines;
    }

    private static long parseTimestampMs(Matcher matcher) {
        try {
            long minutes = Long.parseLong(matcher.group(1));
            long seconds = Long.parseLong(matcher.group(2));
            if (seconds >= 60) {
                return -1;
            }
            String fraction = matcher.group(3);
            long milliseconds = fraction == null ? 0 : Long.parseLong(
                    (fraction + "00").substring(0, 3));
            return minutes * 60_000 + seconds * 1_000 + milliseconds;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String textForCue(String text) {
        text = text.trim();
        if (text.matches("(?i)^(lyricist|composer|arranger|written by|music)"
                + "\\s*(:|\\uFF1A).*")) {
            return "";
        }
        if (text.matches("^(\\u4F5C\\u8BCD|\\u4F5C\\u66F2|\\u7F16\\u66F2|"
                + "\\u5236\\u4F5C\\u4EBA|\\u6F14\\u5531)\\s*(:|\\uFF1A).*")) {
            return "";
        }
        return text;
    }

    private static final class YrcLine {
        final long beginMs;
        final long durationMs;
        final String text;
        final List<LyricSource.Word> words;

        YrcLine(long beginMs, long durationMs, String text, List<LyricSource.Word> words) {
            this.beginMs = beginMs;
            this.durationMs = durationMs;
            this.text = text;
            this.words = words;
        }
    }

    static String request(String endpoint) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Request interrupted");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "DerpFest-SystemUI-Lyric/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("HTTP status " + status);
            }
            return readResponse(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String readResponse(InputStream inputStream) throws IOException {
        StringBuilder response = new StringBuilder();
        int responseSize = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Request interrupted");
                }
                responseSize += line.length();
                if (responseSize > MAX_RESPONSE_SIZE) {
                    throw new IOException("Response is too large");
                }
                response.append(line).append('\n');
            }
        }
        return response.toString();
    }
}
