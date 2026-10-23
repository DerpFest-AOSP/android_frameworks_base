/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone;

import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/**
 * Word-timed lyrics from the Apple Music endpoint used by Accord. The payload is JSON with a
 * {@code lyrics} string, normally TTML whose {@code span} elements carry a begin and end for
 * each word. Line-only LRC in that same field is accepted too.
 */
final class AppleMusicLyricSource implements LyricSource {
    private static final String TAG = "AppleMusicLyricSource";
    private static final String ENDPOINT = "https://test.mrerenk.tr/apple-music/lyrics";
    private static final LruCache<String, LyricSource.Lyrics> CACHE = new LruCache<>(32);

    private String mKey;
    private LyricSource.Lyrics mLyrics;
    private boolean mResolved;

    AppleMusicLyricSource() {
    }

    @Override
    public Lyrics fetch(Track track) {
        Lyrics lyrics = load(track);
        if (lyrics == null || !lyrics.hasWordTiming()) {
            return lyrics;
        }
        TreeMap<Long, Cue> lines = new TreeMap<>();
        for (Cue cue : lyrics.getCues()) {
            lines.put(cue.timestampMs, new Cue(cue.timestampMs, cue.text, cue.translatedText));
        }
        return lines.isEmpty() ? null : new Lyrics(lines);
    }

    @Override
    public Lyrics fetchEnhanced(Track track) {
        Lyrics lyrics = load(track);
        return lyrics != null && lyrics.hasWordTiming() ? lyrics : null;
    }

    private Lyrics load(Track track) {
        if (track == null || TextUtils.isEmpty(track.title)) {
            return null;
        }
        String key = cacheKey(track);
        synchronized (CACHE) {
            Lyrics cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        if (mResolved && key.equals(mKey)) {
            return mLyrics;
        }
        Lyrics lyrics = request(track);
        mKey = key;
        mLyrics = lyrics;
        mResolved = true;
        if (lyrics != null) {
            synchronized (CACHE) {
                CACHE.put(key, lyrics);
            }
        }
        return lyrics;
    }

    private Lyrics request(Track track) {
        try {
            String title = track.title.trim();
            String artist = track.artist == null ? "" : track.artist.trim();
            String album = track.album == null ? "" : track.album.trim();
            String query = artist.isEmpty() ? title : title + " " + artist;
            String lyricUrl = ENDPOINT + "?q=" + encode(query)
                    + "&title=" + encode(title)
                    + "&artist=" + encode(artist);
            if (!album.isEmpty()) {
                lyricUrl += "&album=" + encode(album);
            }
            JSONObject response = new JSONObject(LyricResponseParser.request(lyricUrl));
            if (response.isNull("lyrics")) {
                return null;
            }
            return LyricResponseParser.parseLyricText(response.optString("lyrics", ""));
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Unable to fetch Apple Music lyrics", e);
            return null;
        }
    }

    private String cacheKey(Track track) {
        return String.valueOf(track.title).trim() + "\u0000"
                + String.valueOf(track.artist).trim() + "\u0000"
                + String.valueOf(track.album).trim();
    }

    private String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }
}
