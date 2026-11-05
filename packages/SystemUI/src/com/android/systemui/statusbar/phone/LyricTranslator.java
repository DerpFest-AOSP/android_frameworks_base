/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.icu.util.ULocale;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationManager;
import android.view.translation.TranslationRequest;
import android.view.translation.TranslationRequestValue;
import android.view.translation.TranslationResponse;
import android.view.translation.TranslationResponseValue;
import android.view.translation.TranslationSpec;
import android.view.translation.Translator;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates lyric lines. Language detection uses the platform text classifier. Translation
 * prefers the system on-device translator, then a network request when that session cannot be
 * created. Android System Intelligence can advertise a pair as on-device and still refuse the
 * session when the pack is not enabled, and creating the session does not download it. A
 * translation supplied by the lyric source is kept when it is already in the target language.
 */
final class LyricTranslator {
    private static final String TAG = "LyricTranslator";
    private static final String SYSTEM_TARGET = "system";
    private static final int SAMPLE_LINES = 40;
    private static final int MIN_HINT_HITS = 2;
    /** Common function words, used when the text classifier returns nothing useful. */
    private static final String[][] WORD_HINTS = {
            {"en", "the", "you", "and", "your", "love", "dont", "its", "that", "me", "my"},
            {"de", "und", "ich", "nicht", "dich", "das", "ein", "mir", "auf", "für", "ist"},
            {"es", "que", "por", "con", "una", "los", "las", "amor", "para", "está"},
            {"fr", "les", "des", "une", "pas", "dans", "est", "pour", "avec", "moi"},
            {"pt", "não", "você", "uma", "para", "com", "meu", "pra", "está"},
            {"it", "che", "non", "una", "per", "sono", "amore", "del", "mio"},
            {"nl", "het", "een", "niet", "voor", "van", "jij", "met", "dat"},
            {"pl", "się", "nie", "jest", "że", "mnie", "tak", "jak"},
            {"tr", "bir", "sen", "ben", "için", "değil", "ama", "çok"},
            {"id", "yang", "dan", "aku", "kamu", "tidak", "ini", "untuk"},
    };
    private static final int BATCH_SIZE = 20;
    private static final int ONLINE_BATCH_LINES = 40;
    private static final int ONLINE_BATCH_CHARS = 3500;
    private static final long TRANSLATE_TIMEOUT_MS = 20_000L;
    private static final int ONLINE_CONNECT_TIMEOUT_MS = 5_000;
    private static final int ONLINE_READ_TIMEOUT_MS = 15_000;
    private static final String ONLINE_TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single";
    /** Marks each source line so the translated response can be split back apart. */
    private static final Pattern ONLINE_LINE_MARKER = Pattern.compile("\u27E6(\\d+)\u27E7");

    private LyricTranslator() {
    }

    static LyricSource.Lyrics apply(Context context, int userId, LyricSource.Lyrics lyrics,
            String targetSetting, boolean wifiOnly) {
        if (context == null || lyrics == null || lyrics.getCues().isEmpty()) {
            return lyrics;
        }
        if (context.getUserId() != userId) {
            context = context.createContextAsUser(UserHandle.of(userId), 0);
        }
        ULocale target = targetLocale(targetSetting);
        String sourceLanguage = detectSourceLanguage(context, lyrics, target);
        if (sourceLanguage == null) {
            Log.i(TAG, "Lyric translation skipped: language unknown");
            return fallback(lyrics, target);
        }
        ULocale source = ULocale.forLanguageTag(sourceLanguage);
        if (sameLocale(source, target)) {
            Log.i(TAG, "Lyric translation skipped: already " + languageCode(target));
            return withoutTranslations(lyrics);
        }
        List<LyricSource.Cue> cues = new ArrayList<>(lyrics.getCues());
        ULocale modelSource = modelLocale(source);
        ULocale modelTarget = modelLocale(target);
        List<String> translated = null;
        if (canTranslate(context, source, target, wifiOnly)) {
            translated = translateOnDevice(context, modelSource, modelTarget, cues);
        } else {
            Log.i(TAG, "On-device lyric translation unavailable " + languageCode(source) + "->"
                    + languageCode(target));
        }
        if (translated == null) {
            translated = translateOnline(context, modelSource, modelTarget, cues, wifiOnly);
        }
        if (translated == null) {
            return fallback(lyrics, target);
        }
        TreeMap<Long, LyricSource.Cue> rebuilt = new TreeMap<>();
        int applied = 0;
        for (int i = 0; i < cues.size(); i++) {
            LyricSource.Cue cue = cues.get(i);
            String line = translatedText(cue.text, translated.get(i));
            if (line == null && target != null && "zh".equals(languageCode(target))) {
                line = cue.translatedText;
            }
            if (!TextUtils.isEmpty(line)) {
                applied++;
            }
            rebuilt.put(cue.timestampMs, new LyricSource.Cue(
                    cue.timestampMs, cue.text, line, cue.words));
        }
        Log.i(TAG, "Lyric translation applied " + applied + "/" + cues.size() + " "
                + languageCode(source) + "->" + languageCode(target));
        return rebuilt.isEmpty() ? lyrics : new LyricSource.Lyrics(rebuilt, lyrics.getUnsyncedLyrics());
    }

    private static LyricSource.Lyrics fallback(LyricSource.Lyrics lyrics, ULocale target) {
        if (suppliedTranslationMatches(lyrics, target)) {
            return lyrics;
        }
        return withoutTranslations(lyrics);
    }

    /** Keeps a source translation when it is already written in the requested language. */
    private static boolean suppliedTranslationMatches(LyricSource.Lyrics lyrics, ULocale target) {
        String targetLanguage = languageCode(target);
        if (targetLanguage.isEmpty()) {
            return false;
        }
        StringBuilder sample = new StringBuilder();
        int lines = 0;
        for (LyricSource.Cue cue : lyrics.getCues()) {
            if (cue == null || TextUtils.isEmpty(cue.translatedText)) {
                continue;
            }
            if (sample.length() > 0) {
                sample.append('\n');
            }
            sample.append(cue.translatedText);
            if (++lines >= SAMPLE_LINES) {
                break;
            }
        }
        if (sample.length() == 0) {
            return false;
        }
        String sampleText = sample.toString();
        String detected = detectFromScript(sampleText);
        if (detected == null) {
            detected = guessFromWords(sampleText);
        }
        return targetLanguage.equals(languageOf(detected));
    }

    private static LyricSource.Lyrics withoutTranslations(LyricSource.Lyrics lyrics) {
        TreeMap<Long, LyricSource.Cue> rebuilt = new TreeMap<>();
        for (LyricSource.Cue cue : lyrics.getCues()) {
            rebuilt.put(cue.timestampMs, new LyricSource.Cue(
                    cue.timestampMs, cue.text, null, cue.words));
        }
        return new LyricSource.Lyrics(rebuilt, lyrics.getUnsyncedLyrics());
    }

    private static ULocale targetLocale(String targetSetting) {
        if (TextUtils.isEmpty(targetSetting) || SYSTEM_TARGET.equalsIgnoreCase(targetSetting)) {
            return ULocale.forLocale(Locale.getDefault());
        }
        return ULocale.forLanguageTag(targetSetting.replace('_', '-'));
    }

    private static String detectSourceLanguage(Context context, LyricSource.Lyrics lyrics,
            ULocale target) {
        StringBuilder sample = new StringBuilder();
        int lines = 0;
        for (LyricSource.Cue cue : lyrics.getCues()) {
            if (cue == null || TextUtils.isEmpty(cue.text)) {
                continue;
            }
            if (sample.length() > 0) {
                sample.append('\n');
            }
            sample.append(cue.text);
            if (++lines >= SAMPLE_LINES) {
                break;
            }
        }
        if (sample.length() == 0) {
            return null;
        }
        String sampleText = sample.toString();
        String detected = detectWithClassifier(context, sampleText);
        String guessed = guessFromWords(sampleText);
        String targetLanguage = languageCode(target);
        if (detected == null) {
            if (guessed != null) {
                return guessed;
            }
            String script = detectFromScript(sampleText);
            if (script != null) {
                return script;
            }
            // Latin pop often comes back as "und". English is the useful assumption unless the
            // phone is already set to English, in which case there is nothing to translate.
            if (isMostlyLatin(sampleText) && !"en".equals(targetLanguage)) {
                return "en";
            }
            return null;
        }
        // The classifier follows the device locale. A clear function-word lead in another
        // language is the lyric language.
        if (guessed != null && !languageOf(detected).equals(guessed)
                && languageOf(detected).equals(targetLanguage)
                && !guessed.equals(targetLanguage)) {
            return guessed;
        }
        return detected;
    }

    private static String detectWithClassifier(Context context, String sample) {
        try {
            TextClassificationManager manager =
                    context.getSystemService(TextClassificationManager.class);
            if (manager == null) {
                return null;
            }
            TextClassifier classifier = manager.getTextClassifier();
            TextLanguage language = classifier.detectLanguage(
                    new TextLanguage.Request.Builder(sample).build());
            int count = language.getLocaleHypothesisCount();
            for (int i = 0; i < count; i++) {
                ULocale locale = language.getLocale(i);
                if (locale == null || "und".equals(locale.getLanguage())) {
                    continue;
                }
                String tag = locale.toLanguageTag();
                if (!TextUtils.isEmpty(tag)) {
                    return tag;
                }
            }
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to detect lyric language", e);
            return null;
        }
    }

    private static String guessFromWords(String sample) {
        int[] scores = new int[WORD_HINTS.length];
        int offset = 0;
        while (offset < sample.length()) {
            int start = offset;
            while (offset < sample.length() && Character.isLetter(sample.codePointAt(offset))) {
                offset += Character.charCount(sample.codePointAt(offset));
            }
            if (offset > start) {
                String token = normalizeToken(sample.substring(start, offset));
                if (token.length() > 1) {
                    for (int i = 0; i < WORD_HINTS.length; i++) {
                        for (int hint = 1; hint < WORD_HINTS[i].length; hint++) {
                            if (token.equals(WORD_HINTS[i][hint])) {
                                scores[i]++;
                            }
                        }
                    }
                }
            }
            if (offset < sample.length()) {
                offset += Character.charCount(sample.codePointAt(offset));
            }
        }
        int best = 0;
        int second = 0;
        int bestIndex = -1;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > best) {
                second = best;
                best = scores[i];
                bestIndex = i;
            } else if (scores[i] > second) {
                second = scores[i];
            }
        }
        if (bestIndex < 0 || best < MIN_HINT_HITS || best < second + MIN_HINT_HITS) {
            return null;
        }
        return WORD_HINTS[bestIndex][0];
    }

    private static String normalizeToken(String token) {
        StringBuilder normalized = new StringBuilder(token.length());
        for (int offset = 0; offset < token.length(); ) {
            int codePoint = token.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\'' || codePoint == '’') {
                continue;
            }
            normalized.appendCodePoint(Character.toLowerCase(codePoint));
        }
        return normalized.toString();
    }

    private static boolean isMostlyLatin(String sample) {
        int letters = 0;
        int latin = 0;
        int limit = Math.min(sample.length(), 400);
        for (int offset = 0; offset < limit; ) {
            int codePoint = sample.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            letters++;
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) {
                latin++;
            }
        }
        return letters >= 20 && latin * 5 >= letters * 4;
    }

    private static String languageOf(String tag) {
        if (TextUtils.isEmpty(tag)) {
            return "";
        }
        int dash = tag.indexOf('-');
        String language = dash < 0 ? tag : tag.substring(0, dash);
        return languageCode(ULocale.forLanguageTag(language));
    }

    private static String detectFromScript(String sample) {
        boolean han = false;
        boolean kana = false;
        boolean hangul = false;
        int limit = Math.min(sample.length(), 400);
        for (int offset = 0; offset < limit; ) {
            int codePoint = sample.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (Character.UnicodeScript.of(codePoint)) {
                case HIRAGANA:
                case KATAKANA:
                    kana = true;
                    break;
                case HANGUL:
                    hangul = true;
                    break;
                case HAN:
                    han = true;
                    break;
                case ARABIC:
                    return "ar";
                case CYRILLIC:
                    return "ru";
                case THAI:
                    return "th";
                case DEVANAGARI:
                    return "hi";
                case HEBREW:
                    return "he";
                case GREEK:
                    return "el";
                default:
                    break;
            }
        }
        if (kana) {
            return "ja";
        }
        if (hangul) {
            return "ko";
        }
        if (han) {
            return "zh";
        }
        return null;
    }

    private static boolean canTranslate(Context context, ULocale source, ULocale target,
            boolean wifiOnly) {
        TranslationManager manager = context.getSystemService(TranslationManager.class);
        if (manager == null) {
            return false;
        }
        try {
            Set<TranslationCapability> capabilities = manager.getOnDeviceTranslationCapabilities(
                    TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT);
            Integer state = null;
            for (TranslationCapability capability : capabilities) {
                if (sameLocale(capability.getSourceSpec().getLocale(), source)
                        && sameLocale(capability.getTargetSpec().getLocale(), target)) {
                    state = capability.getState();
                    break;
                }
            }
            if (state == null) {
                // The service has not published this pair. Session creation is still attempted;
                // a refusal falls through to network translation.
                return !wifiOnly || isOnWifi(context);
            }
            if (state == TranslationCapability.STATE_ON_DEVICE) {
                return true;
            }
            if (state == TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD
                    || state == TranslationCapability.STATE_DOWNLOADING
                    || state == TranslationCapability.STATE_REMOVED_AND_AVAILABLE) {
                return !wifiOnly || isOnWifi(context);
            }
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read translation capabilities", e);
            return false;
        }
    }

    private static boolean isOnWifi(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private static List<String> translateOnDevice(Context context, ULocale source, ULocale target,
            List<LyricSource.Cue> cues) {
        TranslationManager manager = context.getSystemService(TranslationManager.class);
        if (manager == null) {
            return null;
        }
        Translator translator = null;
        try {
            translator = manager.createOnDeviceTranslator(new TranslationContext.Builder(
                    new TranslationSpec(source, TranslationSpec.DATA_FORMAT_TEXT),
                    new TranslationSpec(target, TranslationSpec.DATA_FORMAT_TEXT)).build());
            if (translator == null) {
                Log.w(TAG, "On-device translator unavailable " + source + "->" + target);
                return null;
            }
            ArrayList<String> translated = new ArrayList<>();
            for (int start = 0; start < cues.size(); start += BATCH_SIZE) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                int end = Math.min(cues.size(), start + BATCH_SIZE);
                List<String> batch = translateBatch(translator, cues.subList(start, end));
                if (batch == null) {
                    return null;
                }
                translated.addAll(batch);
            }
            return translated.size() == cues.size() ? translated : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to translate lyrics", e);
            return null;
        } finally {
            if (translator != null) {
                translator.destroy();
            }
        }
    }

    /**
     * Used when the on-device pack cannot be opened. The wifi-only preference applies here because
     * the lyric text leaves the device.
     */
    private static List<String> translateOnline(Context context, ULocale source, ULocale target,
            List<LyricSource.Cue> cues, boolean wifiOnly) {
        if (wifiOnly && !isOnWifi(context)) {
            Log.i(TAG, "Network lyric translation skipped: wifi only");
            return null;
        }
        if (!hasInternet(context)) {
            Log.i(TAG, "Network lyric translation skipped: offline");
            return null;
        }
        String sourceLanguage = onlineLanguage(source);
        String targetLanguage = onlineLanguage(target);
        Log.i(TAG, "Translating lyrics over the network " + sourceLanguage + "->"
                + targetLanguage);
        ArrayList<String> translated = new ArrayList<>();
        int start = 0;
        while (start < cues.size()) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            int end = start;
            int chars = 0;
            while (end < cues.size() && end - start < ONLINE_BATCH_LINES) {
                String text = cueText(cues.get(end));
                if (end > start && chars + text.length() > ONLINE_BATCH_CHARS) {
                    break;
                }
                chars += text.length();
                end++;
            }
            List<String> batch = translateOnlineBatch(
                    sourceLanguage, targetLanguage, cues.subList(start, end));
            if (batch == null) {
                Log.w(TAG, "Network lyric translation failed " + sourceLanguage + "->"
                        + targetLanguage);
                return null;
            }
            translated.addAll(batch);
            start = end;
        }
        return translated.size() == cues.size() ? translated : null;
    }

    private static List<String> translateOnlineBatch(String sourceLanguage, String targetLanguage,
            List<LyricSource.Cue> cues) {
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            if (i > 0) {
                payload.append('\n');
            }
            payload.append('\u27E6').append(i).append('\u27E7').append(' ')
                    .append(cueText(cues.get(i)));
        }
        HttpURLConnection connection = null;
        try {
            String body = "client=gtx&sl=" + urlEncode(sourceLanguage)
                    + "&tl=" + urlEncode(targetLanguage)
                    + "&dt=t&q=" + urlEncode(payload.toString());
            connection = (HttpURLConnection) new URL(ONLINE_TRANSLATE_URL).openConnection();
            connection.setConnectTimeout(ONLINE_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(ONLINE_READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DerpFest-SystemUI-Lyric/1.0");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status < HttpURLConnection.HTTP_OK
                    || status >= HttpURLConnection.HTTP_MULT_CHOICE) {
                Log.w(TAG, "Network lyric translation HTTP " + status);
                return null;
            }
            return parseOnlineTranslation(readStream(connection.getInputStream()), cues.size());
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Network lyric translation request failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static List<String> parseOnlineTranslation(String response, int lineCount)
            throws JSONException {
        JSONArray segments = new JSONArray(response).getJSONArray(0);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < segments.length(); i++) {
            joined.append(segments.getJSONArray(i).optString(0, ""));
        }
        String[] lines = new String[lineCount];
        int found = 0;
        Matcher matcher = ONLINE_LINE_MARKER.matcher(joined);
        int previousIndex = -1;
        int previousContent = -1;
        while (matcher.find()) {
            if (previousIndex >= 0 && previousIndex < lineCount) {
                lines[previousIndex] = joined.substring(previousContent, matcher.start());
                found++;
            }
            previousIndex = Integer.parseInt(matcher.group(1));
            previousContent = matcher.end();
        }
        if (previousIndex >= 0 && previousIndex < lineCount) {
            lines[previousIndex] = joined.substring(previousContent);
            found++;
        }
        if (found != lineCount) {
            return null;
        }
        ArrayList<String> translated = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            if (lines[i] == null) {
                return null;
            }
            String text = lines[i].trim();
            translated.add(text.isEmpty() ? null : text);
        }
        return translated;
    }

    private static String readStream(InputStream inputStream) throws IOException {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Request interrupted");
                }
                response.append(line);
            }
        }
        return response.toString();
    }

    private static String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    private static String cueText(LyricSource.Cue cue) {
        return cue == null || cue.text == null ? "" : cue.text;
    }

    private static String onlineLanguage(ULocale locale) {
        String language = languageCode(locale);
        if (!"zh".equals(language)) {
            return language.isEmpty() ? "auto" : language;
        }
        return isTraditionalChinese(locale) ? "zh-TW" : "zh-CN";
    }

    private static boolean hasInternet(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static List<String> translateBatch(Translator translator, List<LyricSource.Cue> cues) {
        TranslationRequest.Builder request = new TranslationRequest.Builder();
        for (LyricSource.Cue cue : cues) {
            request.addTranslationRequestValue(TranslationRequestValue.forText(
                    cue == null || cue.text == null ? "" : cue.text));
        }
        AtomicReference<TranslationResponse> response = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        translator.translate(request.build(), null, Runnable::run, value -> {
            response.set(value);
            latch.countDown();
        });
        try {
            if (!latch.await(TRANSLATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        TranslationResponse translated = response.get();
        if (translated == null
                || translated.getTranslationStatus()
                != TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
            return null;
        }
        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            TranslationResponseValue value = translated.getTranslationResponseValues().get(i);
            if (value == null
                    || value.getStatusCode() != TranslationResponseValue.STATUS_SUCCESS
                    || value.getText() == null) {
                lines.add(null);
            } else {
                lines.add(value.getText().toString());
            }
        }
        return lines;
    }

    private static String translatedText(String original, String translated) {
        if (TextUtils.isEmpty(translated)) {
            return null;
        }
        String originalText = original == null ? "" : original.trim();
        String translatedText = translated.trim();
        if (translatedText.isEmpty() || translatedText.equalsIgnoreCase(originalText)) {
            return null;
        }
        return translatedText;
    }

    private static boolean sameLocale(ULocale first, ULocale second) {
        String firstLanguage = languageCode(first);
        String secondLanguage = languageCode(second);
        if (firstLanguage.isEmpty() || !firstLanguage.equals(secondLanguage)) {
            return false;
        }
        if (!"zh".equals(firstLanguage)) {
            return true;
        }
        return isTraditionalChinese(first) == isTraditionalChinese(second);
    }

    /**
     * The on-device translator matches language codes such as {@code de} and {@code zh_Hant}.
     * A full default locale like {@code de_DE} is rejected.
     */
    private static ULocale modelLocale(ULocale locale) {
        String language = languageCode(locale);
        if (language.isEmpty()) {
            return locale;
        }
        if ("zh".equals(language) && isTraditionalChinese(locale)) {
            return ULocale.forLanguageTag("zh-Hant");
        }
        return new ULocale(language);
    }

    private static String languageCode(ULocale locale) {
        if (locale == null) {
            return "";
        }
        String language = locale.toLocale().getLanguage();
        if (TextUtils.isEmpty(language)) {
            language = locale.getLanguage();
        }
        return language == null ? "" : language;
    }

    private static boolean isTraditionalChinese(ULocale locale) {
        if ("Hant".equals(locale.getScript())) {
            return true;
        }
        String country = locale.getCountry();
        return "TW".equals(country) || "HK".equals(country) || "MO".equals(country);
    }
}
