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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Translates lyric lines on device. Language detection uses the platform text classifier, and
 * translation uses the system on-device translator. A supplied translation is kept only when the
 * target language is Chinese and on-device translation is unavailable.
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
    private static final long TRANSLATE_TIMEOUT_MS = 20_000L;

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
        if (!canTranslate(context, source, target, wifiOnly)) {
            Log.i(TAG, "Lyric translation unavailable " + languageCode(source) + "->"
                    + languageCode(target));
            return fallback(lyrics, target);
        }
        List<LyricSource.Cue> cues = new ArrayList<>(lyrics.getCues());
        List<String> translated = translate(context, modelLocale(source), modelLocale(target), cues);
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
        if (target != null && "zh".equals(target.getLanguage())) {
            return lyrics;
        }
        return withoutTranslations(lyrics);
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
                // An empty or differently tagged list means the service has not published this
                // pair yet. Creating the translator is what makes it download the model.
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

    private static List<String> translate(Context context, ULocale source, ULocale target,
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
