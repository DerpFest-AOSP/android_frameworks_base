/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Looks up lyrics on LRCLIB. Used when the user has not configured another lyric source.
 */
final class LrcLibLyricSource implements LyricSource {
    private static final String TAG = "LrcLibLyricSource";
    private static final String SEARCH_URL = "https://lrclib.net/api/search?q=";
    private static final String USER_AGENT = "DerpFest-SystemUI-Lyric/1.0";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE_SIZE = 2 * 1024 * 1024;
    private static final int MAX_RESULTS = 10;
    private static final int MAX_REQUESTS = 4;
    private static final int MAX_ATTEMPTS = 2;
    private static final long NEGATIVE_CACHE_TTL_MS = 12 * 60 * 60 * 1000L;
    private static final long INITIAL_RETRY_DELAY_MS = 1_500L;

    private static final Pattern SEPARATOR = Pattern.compile("\\s+[-–—|•]\\s+");
    private static final Pattern PARENTHETICAL = Pattern.compile(
            "(?i)\\s*[\\(\\[]([^\\)\\]]*(?:feat|featuring|ft\\.?|with|remaster|live|video"
                    + "|version|edit|acoustic|single|studio|mono|stereo|re-recorded|lyric|mv"
                    + "|hd|hq|audio|visualizer|official)[^\\)\\]]*)[\\]\\)]");
    private static final Pattern META_TERMS = Pattern.compile(
            "(?i)\\s*\\b(official\\s+video|official\\s+audio|lyric\\s+video"
                    + "|official\\s+music\\s+video|music\\s+video|lyric\\s+card|lyric|lyrics"
                    + "|video|mv|hd|hq|audio|visualizer|uncensored|clean\\s+version"
                    + "|extended\\s+mix)\\b");
    private static final Pattern TRAILING_FEAT = Pattern.compile(
            "(?i)\\s+\\b(feat\\.?|featuring|ft\\.?|with)\\b.*");
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "(?i)\\s*[,/;]\\s*|\\s+\\b(feat\\.?|featuring|ft\\.?|and|&)\\b\\s+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern QUOTES = Pattern.compile("[\"']");

    private static final LruCache<String, Lookup> CACHE = new LruCache<>(50);

    LrcLibLyricSource() {
    }

    @Override
    public LyricSource.Lyrics fetch(LyricSource.Track track) {
        if (track == null || TextUtils.isEmpty(track.title) || TextUtils.isEmpty(track.artist)) {
            return null;
        }
        return lookup(track.artist, track.title, track.durationMs);
    }

    private LyricSource.Lyrics lookup(String artist, String song, long durationMs) {
        synchronized (CACHE) {
            return lookupLocked(artist, song, durationMs);
        }
    }

    private LyricSource.Lyrics lookupLocked(String artist, String song, long durationMs) {
        String originalKey = cacheKey("track", normalize(artist), normalize(song));
        Lookup cached = CACHE.get(originalKey);
        if (cached != null) {
            if (cached.found) {
                return toLyrics(cached);
            }
            if (SystemClock.elapsedRealtime() - cached.timestampMs < NEGATIVE_CACHE_TTL_MS) {
                return null;
            }
            CACHE.remove(originalKey);
        }

        List<String[]> candidates = searchCandidates(artist, song);
        AtomicInteger requests = new AtomicInteger();
        boolean transientFailure = false;
        for (String[] candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            if (requests.get() >= MAX_REQUESTS) {
                transientFailure = true;
                break;
            }
            Lookup result = fetchCandidate(candidate[0], candidate[1], durationMs, requests);
            if (result == null) {
                transientFailure = true;
                continue;
            }
            if (result.found) {
                CACHE.put(originalKey, result);
                return toLyrics(result);
            }
        }
        if (!transientFailure) {
            CACHE.put(originalKey, Lookup.notFound(SystemClock.elapsedRealtime()));
        }
        return null;
    }

    private Lookup fetchCandidate(String artist, String song, long durationMs,
            AtomicInteger requests) {
        String key = cacheKey("query", artist, song);
        Lookup cached = CACHE.get(key);
        if (cached != null) {
            if (cached.found) {
                return cached;
            }
            if (SystemClock.elapsedRealtime() - cached.timestampMs < NEGATIVE_CACHE_TTL_MS) {
                return cached;
            }
            CACHE.remove(key);
        }

        int attempt = 0;
        long delayMs = INITIAL_RETRY_DELAY_MS;
        while (attempt < MAX_ATTEMPTS && !Thread.currentThread().isInterrupted()) {
            if (requests.get() >= MAX_REQUESTS) {
                return null;
            }
            requests.incrementAndGet();
            HttpURLConnection connection = null;
            try {
                String query = URLEncoder.encode(artist + " " + song, StandardCharsets.UTF_8.name());
                connection = (HttpURLConnection) new URL(SEARCH_URL + query).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) {
                    Lookup match = bestMatch(new JSONArray(readResponse(connection.getInputStream())),
                            artist, song, durationMs);
                    if (match != null) {
                        CACHE.put(key, match);
                        return match;
                    }
                    return Lookup.notFound(SystemClock.elapsedRealtime());
                }
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    return Lookup.notFound(SystemClock.elapsedRealtime());
                }
                if (status == 429) {
                    int retryAfter = parseRetryAfter(connection.getHeaderField("Retry-After"));
                    if (retryAfter > 15) {
                        return null;
                    }
                    attempt++;
                    if (!sleep((retryAfter > 0 ? retryAfter : 5) * 1000L)) {
                        return null;
                    }
                    continue;
                }
                if (status == 408 || status == 500 || status == 502 || status == 503
                        || status == 504) {
                    attempt++;
                    if (!sleep(delayMs)) {
                        return null;
                    }
                    delayMs *= 2;
                    continue;
                }
                return null;
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Unable to search LRCLIB", e);
                attempt++;
                if (!sleep(delayMs)) {
                    return null;
                }
                delayMs *= 2;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return null;
    }

    private Lookup bestMatch(JSONArray results, String artist, String song, long durationMs)
            throws JSONException {
        int bestScore = -1;
        Lookup best = null;
        int count = Math.min(results.length(), MAX_RESULTS);
        for (int i = 0; i < count; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String resultArtist = text(item, "artistName");
            String resultTitle = text(item, "trackName");
            long resultDurationMs = item.optInt("duration", 0) * 1000L;
            String plain = text(item, "plainLyrics");
            String synced = text(item, "syncedLyrics");
            if (plain.isEmpty() && synced.isEmpty()) {
                continue;
            }
            int metaScore = matchScore(artist, song, resultArtist, resultTitle);
            boolean comparableDuration = durationMs > 0L && resultDurationMs > 0L;
            long durationDifference = comparableDuration
                    ? Math.abs(durationMs - resultDurationMs) : Long.MAX_VALUE;
            int durationModifier;
            if (!comparableDuration) {
                durationModifier = 0;
            } else if (durationDifference <= 2_000L) {
                durationModifier = 30;
            } else if (durationDifference <= 5_000L) {
                durationModifier = 20;
            } else if (durationDifference <= 10_000L) {
                durationModifier = 5;
            } else {
                durationModifier = -50;
            }
            int combinedScore = metaScore + durationModifier;
            boolean canUsePlain = metaScore >= 80 && !plain.isEmpty();
            boolean canUseSynced = !synced.isEmpty() && metaScore >= 80
                    && ((comparableDuration && durationDifference <= 5_000L)
                    || (!comparableDuration && metaScore >= 90));
            if ((canUsePlain || canUseSynced) && combinedScore > bestScore) {
                bestScore = combinedScore;
                best = Lookup.found(
                        canUsePlain ? plain : null,
                        canUseSynced ? synced : null);
            }
        }
        return best;
    }

    private LyricSource.Lyrics toLyrics(Lookup lookup) {
        if (!TextUtils.isEmpty(lookup.syncedLyrics)) {
            try {
                JSONObject response = new JSONObject();
                response.put("lrc", new JSONObject().put("lyric", lookup.syncedLyrics));
                LyricSource.Lyrics lyrics = LyricResponseParser.parseLyrics(response);
                if (lyrics != null) {
                    return lyrics;
                }
            } catch (JSONException e) {
                Log.w(TAG, "Unable to parse LRCLIB synced lyrics", e);
            }
        }
        if (TextUtils.isEmpty(lookup.plainLyrics)) {
            return null;
        }
        return new LyricSource.Lyrics(new TreeMap<>(), lookup.plainLyrics);
    }

    private List<String[]> searchCandidates(String artist, String song) {
        ArrayList<String[]> candidates = new ArrayList<>();
        String cleanArtist = cleanArtist(artist);
        String moderateSong = cleanTitle(song, false);
        addCandidate(candidates, cleanArtist, moderateSong);
        addCandidate(candidates, artist, song);
        addCandidate(candidates, cleanArtist, cleanTitle(song, true));
        return candidates;
    }

    private void addCandidate(List<String[]> candidates, String artist, String song) {
        if (TextUtils.isEmpty(artist) || TextUtils.isEmpty(song)) {
            return;
        }
        for (String[] existing : candidates) {
            if (artist.equals(existing[0]) && song.equals(existing[1])) {
                return;
            }
        }
        candidates.add(new String[] {artist, song});
    }

    private String cleanTitle(String title, boolean splitSeparators) {
        if (TextUtils.isEmpty(title)) {
            return title;
        }
        String cleaned = title;
        if (splitSeparators) {
            String[] parts = SEPARATOR.split(cleaned, 2);
            cleaned = parts.length == 0 ? "" : parts[0];
        }
        cleaned = PARENTHETICAL.matcher(cleaned).replaceAll("");
        cleaned = META_TERMS.matcher(cleaned).replaceAll("");
        cleaned = TRAILING_FEAT.matcher(cleaned).replaceAll("");
        cleaned = QUOTES.matcher(cleaned).replaceAll("");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ");
        return cleaned.trim();
    }

    private String cleanArtist(String artist) {
        if (TextUtils.isEmpty(artist)) {
            return artist;
        }
        String[] parts = ARTIST_SPLIT.split(artist, 2);
        return (parts.length == 0 ? artist : parts[0]).trim();
    }

    private int matchScore(String queryArtist, String queryTitle, String resultArtist,
            String resultTitle) {
        String normQueryArtist = normalize(queryArtist);
        String normQueryTitle = normalize(queryTitle);
        String normResultArtist = normalize(resultArtist);
        String normResultTitle = normalize(resultTitle);
        if (normQueryArtist.isEmpty() || normQueryTitle.isEmpty()) {
            return 0;
        }
        if (normResultArtist.equals(normQueryArtist) && normResultTitle.equals(normQueryTitle)) {
            return 100;
        }
        if (normResultTitle.equals(normQueryTitle) && normResultArtist.contains(normQueryArtist)) {
            return 90;
        }
        if (normResultArtist.equals(normQueryArtist) && normResultTitle.contains(normQueryTitle)) {
            return 80;
        }
        if (normResultArtist.contains(normQueryArtist)
                && normResultTitle.contains(normQueryTitle)) {
            return 60;
        }
        return 0;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = NON_ALNUM.matcher(value.toLowerCase(Locale.US)).replaceAll(" ");
        return WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
    }

    private String cacheKey(String scope, String artist, String song) {
        return scope + "\u0000" + artist + "\u0000" + song;
    }

    private String text(JSONObject item, String key) {
        if (item.isNull(key)) {
            return "";
        }
        return item.optString(key, "");
    }

    private int parseRetryAfter(String header) {
        if (TextUtils.isEmpty(header)) {
            return -1;
        }
        try {
            return Integer.parseInt(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return !Thread.currentThread().isInterrupted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String readResponse(InputStream inputStream) throws IOException {
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

    private static final class Lookup {
        final boolean found;
        final String plainLyrics;
        final String syncedLyrics;
        final long timestampMs;

        private Lookup(boolean found, String plainLyrics, String syncedLyrics, long timestampMs) {
            this.found = found;
            this.plainLyrics = plainLyrics;
            this.syncedLyrics = syncedLyrics;
            this.timestampMs = timestampMs;
        }

        static Lookup found(String plainLyrics, String syncedLyrics) {
            return new Lookup(true, plainLyrics, syncedLyrics, 0L);
        }

        static Lookup notFound(long timestampMs) {
            return new Lookup(false, null, null, timestampMs);
        }
    }
}
