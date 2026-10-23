/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.text.TextUtils;
import android.util.Xml;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            "\\[(\\d{1,3}):(\\d{1,2})(?:[\\.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET_PATTERN = Pattern.compile("\\[offset:([+-]?\\d+)\\]");
    private static final Pattern YRC_LINE_PATTERN = Pattern.compile("\\[(\\d+),(\\d+)\\](.*)");
    private static final Pattern YRC_WORD_PATTERN = Pattern.compile(
            "\\((\\d+),(\\d+),\\d+\\)([^\\(]*)");
    private static final Pattern WORD_TAG_PATTERN = Pattern.compile(
            "<(\\d{1,3}):(\\d{1,2})(?:[\\.:](\\d{1,3}))?>");

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

        LyricSource.Lyrics wordTimed = parseWordTimedDocument(enhanced);
        if (wordTimed == null) {
            wordTimed = parseWordTimedDocument(original);
        }
        if (wordTimed != null && wordTimed.hasWordTiming()) {
            return wordTimed;
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

    /**
     * Parses a lyric document that may be Apple Music TTML, enhanced LRC with per-word times,
     * plain LRC, or YRC.
     */
    static LyricSource.Lyrics parseLyricText(String lyricText) {
        if (TextUtils.isEmpty(lyricText)) {
            return null;
        }
        LyricSource.Lyrics wordTimed = parseWordTimedDocument(lyricText);
        if (wordTimed != null) {
            return wordTimed;
        }
        if (looksLikeTtml(lyricText.trim())) {
            return null;
        }
        try {
            JSONObject response = new JSONObject();
            response.put("lrc", new JSONObject().put("lyric", lyricText));
            return parseLyrics(response);
        } catch (JSONException e) {
            return null;
        }
    }

    private static LyricSource.Lyrics parseWordTimedDocument(String lyricText) {
        if (TextUtils.isEmpty(lyricText)) {
            return null;
        }
        String trimmed = lyricText.trim();
        if (looksLikeTtml(trimmed)) {
            return parseTtml(trimmed);
        }
        return parseEnhancedLrc(trimmed);
    }

    private static boolean looksLikeTtml(String text) {
        return text.contains("<tt ") || text.contains("<tt>") || text.contains("<p ")
                || text.contains("<p>");
    }

    private static LyricSource.Lyrics parseTtml(String xml) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            } catch (XmlPullParserException ignored) {
            }
            parser.setInput(new StringReader(xml));
            TreeMap<Long, LyricSource.Cue> cues = new TreeMap<>();
            ArrayList<TextPiece> pieces = null;
            StringBuilder spanText = null;
            StringBuilder gapText = null;
            long paragraphBeginMs = -1;
            long paragraphEndMs = -1;
            long spanBeginMs = -1;
            long spanEndMs = -1;
            int paragraphDepth = 0;
            int spanDepth = 0;
            boolean skipParagraph = false;
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("p".equals(name)) {
                        if (paragraphDepth == 0) {
                            pieces = new ArrayList<>();
                            gapText = new StringBuilder();
                            spanText = null;
                            paragraphBeginMs = attributeTimeMs(parser, "begin");
                            paragraphEndMs = attributeTimeMs(parser, "end");
                            skipParagraph = isBackgroundParagraph(parser);
                        }
                        paragraphDepth++;
                    } else if ("span".equals(name) && paragraphDepth > 0 && !skipParagraph) {
                        if (spanDepth == 0) {
                            flushGap(pieces, gapText);
                            spanText = new StringBuilder();
                            spanBeginMs = attributeTimeMs(parser, "begin");
                            spanEndMs = attributeTimeMs(parser, "end");
                        }
                        spanDepth++;
                    } else if ("br".equals(name) && paragraphDepth > 0 && !skipParagraph) {
                        appendSpace(spanDepth > 0 ? spanText : gapText);
                    }
                } else if (eventType == XmlPullParser.TEXT && paragraphDepth > 0
                        && !skipParagraph) {
                    StringBuilder target = spanDepth > 0 ? spanText : gapText;
                    String chunk = parser.getText();
                    if (target != null && chunk != null) {
                        target.append(chunk);
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String name = parser.getName();
                    if ("span".equals(name) && spanDepth > 0) {
                        spanDepth--;
                        if (spanDepth == 0) {
                            flushSpan(pieces, spanText, spanBeginMs, spanEndMs);
                            spanText = null;
                        }
                    } else if ("p".equals(name) && paragraphDepth > 0) {
                        paragraphDepth--;
                        if (paragraphDepth == 0 && !skipParagraph) {
                            flushGap(pieces, gapText);
                            addParagraph(cues, pieces, paragraphBeginMs, paragraphEndMs);
                        }
                    }
                }
                eventType = parser.next();
            }
            return cues.isEmpty() ? null : new LyricSource.Lyrics(cues);
        } catch (XmlPullParserException | IOException | RuntimeException e) {
            return null;
        }
    }

    private static void addParagraph(TreeMap<Long, LyricSource.Cue> cues, List<TextPiece> pieces,
            long paragraphBeginMs, long paragraphEndMs) {
        if (pieces == null || pieces.isEmpty()) {
            return;
        }
        assignUntimedPieces(pieces, paragraphBeginMs, paragraphEndMs);
        StringBuilder text = new StringBuilder();
        ArrayList<LyricSource.Word> words = new ArrayList<>();
        for (TextPiece piece : pieces) {
            if (TextUtils.isEmpty(piece.text)) {
                continue;
            }
            text.append(piece.text);
            words.add(new LyricSource.Word(piece.beginMs, piece.endMs, piece.text));
        }
        String line = text.toString();
        if (TextUtils.isEmpty(textForCue(line)) || words.isEmpty()) {
            return;
        }
        long beginMs = paragraphBeginMs >= 0 ? paragraphBeginMs : words.get(0).beginMs;
        if (cues.containsKey(beginMs)) {
            return;
        }
        cues.put(beginMs, new LyricSource.Cue(beginMs, line, null, words));
    }

    private static void assignUntimedPieces(List<TextPiece> pieces, long paragraphBeginMs,
            long paragraphEndMs) {
        for (int i = 0; i < pieces.size(); i++) {
            TextPiece piece = pieces.get(i);
            if (piece.timed && piece.beginMs >= 0) {
                continue;
            }
            long beginMs = paragraphBeginMs;
            for (int previous = i - 1; previous >= 0; previous--) {
                if (pieces.get(previous).endMs >= 0) {
                    beginMs = pieces.get(previous).endMs;
                    break;
                }
            }
            long endMs = paragraphEndMs >= 0 ? paragraphEndMs : beginMs;
            for (int next = i + 1; next < pieces.size(); next++) {
                TextPiece following = pieces.get(next);
                if (following.timed && following.beginMs >= beginMs) {
                    endMs = following.beginMs;
                    break;
                }
            }
            if (endMs < beginMs) {
                endMs = beginMs;
            }
            pieces.set(i, new TextPiece(piece.text, Math.max(0, beginMs), endMs, true));
        }
        for (int i = 0; i < pieces.size(); i++) {
            TextPiece piece = pieces.get(i);
            if (piece.endMs >= piece.beginMs && piece.beginMs >= 0) {
                continue;
            }
            long endMs = paragraphEndMs >= 0 ? paragraphEndMs : piece.beginMs;
            pieces.set(i, new TextPiece(piece.text, Math.max(0, piece.beginMs), endMs, true));
        }
    }

    private static void flushGap(List<TextPiece> pieces, StringBuilder gapText) {
        if (pieces == null || gapText == null || gapText.length() == 0) {
            if (gapText != null) {
                gapText.setLength(0);
            }
            return;
        }
        pieces.add(new TextPiece(gapText.toString(), -1, -1, false));
        gapText.setLength(0);
    }

    private static void flushSpan(List<TextPiece> pieces, StringBuilder spanText, long beginMs,
            long endMs) {
        if (pieces == null || spanText == null || spanText.length() == 0) {
            return;
        }
        pieces.add(new TextPiece(spanText.toString(), beginMs, endMs, beginMs >= 0));
    }

    private static void appendSpace(StringBuilder text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (!Character.isWhitespace(text.charAt(text.length() - 1))) {
            text.append(' ');
        }
    }

    private static boolean isBackgroundParagraph(XmlPullParser parser) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String name = parser.getAttributeName(i);
            if (name == null) {
                continue;
            }
            int colon = name.lastIndexOf(':');
            String local = colon >= 0 ? name.substring(colon + 1) : name;
            if (!"role".equals(local) && !"songPart".equals(local)) {
                continue;
            }
            String value = parser.getAttributeValue(i);
            if (value == null) {
                continue;
            }
            String normalized = value.toLowerCase(Locale.US);
            if (normalized.contains("background") || normalized.contains("bg")) {
                return true;
            }
        }
        return false;
    }

    private static long attributeTimeMs(XmlPullParser parser, String attribute) {
        String value = parser.getAttributeValue(null, attribute);
        if (value == null) {
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                String name = parser.getAttributeName(i);
                if (attribute.equals(name) || (name != null && name.endsWith(":" + attribute))) {
                    value = parser.getAttributeValue(i);
                    break;
                }
            }
        }
        return parseClockTimeMs(value);
    }

    private static LyricSource.Lyrics parseEnhancedLrc(String lyric) {
        if (TextUtils.isEmpty(lyric) || !WORD_TAG_PATTERN.matcher(lyric).find()) {
            return null;
        }
        long offsetMs = getOffsetMs(lyric);
        ArrayList<BuiltLine> lines = new ArrayList<>();
        for (String rawLine : lyric.split("\\r?\\n")) {
            Matcher timestamps = TIMESTAMP_PATTERN.matcher(rawLine);
            ArrayList<Long> lineTimes = new ArrayList<>();
            int textStart = 0;
            while (timestamps.find()) {
                if (timestamps.start() != textStart) {
                    break;
                }
                long timestampMs = parseTimestampMs(timestamps);
                textStart = timestamps.end();
                if (timestampMs >= 0) {
                    lineTimes.add(Math.max(0, timestampMs + offsetMs));
                }
            }
            if (lineTimes.isEmpty()) {
                continue;
            }
            String body = rawLine.substring(textStart);
            ArrayList<LyricSource.Word> words = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Matcher wordTags = WORD_TAG_PATTERN.matcher(body);
            int cursor = 0;
            long openBeginMs = lineTimes.get(0);
            boolean open = false;
            while (wordTags.find()) {
                String before = body.substring(cursor, wordTags.start());
                if (!before.isEmpty() && open) {
                    words.add(new LyricSource.Word(openBeginMs, -1, before));
                    text.append(before);
                } else if (!before.isEmpty()) {
                    words.add(new LyricSource.Word(lineTimes.get(0), -1, before));
                    text.append(before);
                }
                openBeginMs = parseTimestampMs(wordTags);
                if (openBeginMs < 0) {
                    words.clear();
                    break;
                }
                openBeginMs = Math.max(0, openBeginMs + offsetMs);
                open = true;
                cursor = wordTags.end();
            }
            if (words.isEmpty() && !open) {
                String lineText = textForCue(body);
                if (!TextUtils.isEmpty(lineText)) {
                    for (Long timestampMs : lineTimes) {
                        lines.add(new BuiltLine(timestampMs, lineText, null));
                    }
                }
                continue;
            }
            String tail = body.substring(cursor);
            if (!tail.isEmpty() && open) {
                words.add(new LyricSource.Word(openBeginMs, -1, tail));
                text.append(tail);
            }
            String lineText = text.toString();
            if (TextUtils.isEmpty(textForCue(lineText)) || words.isEmpty()) {
                continue;
            }
            for (int i = 0; i < words.size() - 1; i++) {
                LyricSource.Word word = words.get(i);
                LyricSource.Word next = words.get(i + 1);
                words.set(i, new LyricSource.Word(word.beginMs,
                        next.beginMs > word.beginMs ? next.beginMs : word.beginMs, word.text));
            }
            for (Long timestampMs : lineTimes) {
                lines.add(new BuiltLine(timestampMs, lineText, new ArrayList<>(words)));
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        TreeMap<Long, LyricSource.Cue> cues = new TreeMap<>();
        boolean anyWords = false;
        for (int i = 0; i < lines.size(); i++) {
            BuiltLine line = lines.get(i);
            List<LyricSource.Word> words = line.words;
            if (words != null && !words.isEmpty()) {
                anyWords = true;
                LyricSource.Word last = words.get(words.size() - 1);
                long endMs = last.beginMs;
                if (i + 1 < lines.size() && lines.get(i + 1).timestampMs > last.beginMs) {
                    endMs = lines.get(i + 1).timestampMs;
                } else {
                    endMs = last.beginMs + Math.max(400L, last.text.length() * 80L);
                }
                words.set(words.size() - 1, new LyricSource.Word(last.beginMs, endMs, last.text));
            }
            cues.put(line.timestampMs, new LyricSource.Cue(
                    line.timestampMs, line.text, null, words));
        }
        return anyWords && !cues.isEmpty() ? new LyricSource.Lyrics(cues) : null;
    }

    private static long parseClockTimeMs(String raw) {
        if (raw == null) {
            return -1;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return -1;
        }
        try {
            if (value.endsWith("ms") && value.indexOf(':') < 0) {
                return Long.parseLong(value.substring(0, value.length() - 2).trim());
            }
            if (value.endsWith("s") && value.indexOf(':') < 0) {
                return Math.round(Double.parseDouble(
                        value.substring(0, value.length() - 1).trim()) * 1000d);
            }
            String[] parts = value.split(":");
            long hours = 0;
            long minutes = 0;
            double seconds;
            if (parts.length == 3) {
                hours = Long.parseLong(parts[0]);
                minutes = Long.parseLong(parts[1]);
                seconds = Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                minutes = Long.parseLong(parts[0]);
                seconds = Double.parseDouble(parts[1]);
            } else if (parts.length == 1) {
                seconds = Double.parseDouble(parts[0]);
            } else {
                return -1;
            }
            if (hours < 0 || minutes < 0 || seconds < 0) {
                return -1;
            }
            return hours * 3_600_000L + minutes * 60_000L + Math.round(seconds * 1000d);
        } catch (NumberFormatException e) {
            return -1;
        }
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

    private static final class TextPiece {
        final String text;
        final long beginMs;
        final long endMs;
        final boolean timed;

        TextPiece(String text, long beginMs, long endMs, boolean timed) {
            this.text = text;
            this.beginMs = beginMs;
            this.endMs = endMs;
            this.timed = timed;
        }
    }

    private static final class BuiltLine {
        final long timestampMs;
        final String text;
        final List<LyricSource.Word> words;

        BuiltLine(long timestampMs, String text, List<LyricSource.Word> words) {
            this.timestampMs = timestampMs;
            this.text = text;
            this.words = words;
        }
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
