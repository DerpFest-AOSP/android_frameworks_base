/*
 *  Copyright (C) 2018 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.android.systemui.derpfest.header;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.util.Calendar;

public class FileHeaderProvider implements
        StatusBarHeaderMachine.IStatusBarHeaderProvider {

    public static final String TAG = "FileHeaderProvider";
    private static final boolean DEBUG = false;
    private static final String HEADER_FILE_NAME = "custom_file_header_image";

    private Context mContext;
    private Drawable mImage = null;
    private String mLoadedPath;
    private long mLoadedLength;
    private long mLoadedModified;

    public FileHeaderProvider(Context context) {
        mContext = context;
        if (isCustomHeaderEnabled()) {
            loadHeaderImage();
        }
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public void settingsChanged(Uri uri) {
        if (isCustomHeaderEnabled()) {
            loadHeaderImage();
        } else {
            releaseImage();
        }
    }
    
    private boolean isCustomHeaderEnabled() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
    }
    
    private String getCustomHeaderPath() {
        return Settings.System.getStringForUser(mContext.getContentResolver(),
                    Settings.System.STATUS_BAR_FILE_HEADER_IMAGE,
                    UserHandle.USER_CURRENT);
    }

    @Override
    public void enableProvider() {
        settingsChanged(null);
    }

    @Override
    public void disableProvider() {
        releaseImage();
    }

    private void releaseImage() {
        mImage = null;
        mLoadedPath = null;
        mLoadedLength = 0;
        mLoadedModified = 0;
    }

    private void loadHeaderImage() {
        if (mContext == null) {
            return;
        }
        String path = getCustomHeaderPath();
        if (path == null || path.isEmpty()) {
            releaseImage();
            return;
        }
        File file = new File(path);
        long length = file.length();
        long modified = file.lastModified();
        if (mImage != null && path.equals(mLoadedPath)
                && length == mLoadedLength && modified == mLoadedModified) {
            return;
        }
        Bitmap bitmap = decodeSampled(file);
        if (bitmap == null) {
            Log.d(TAG, "Failed to decode bitmap from " + path);
            releaseImage();
            return;
        }
        mImage = new BitmapDrawable(mContext.getResources(), bitmap);
        mLoadedPath = path;
        mLoadedLength = length;
        mLoadedModified = modified;
    }

    /** The header is only as wide as the display, so a camera photo need not stay full size. */
    private Bitmap decodeSampled(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        int maxEdge = Math.max(metrics.widthPixels, metrics.heightPixels);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(Math.max(bounds.outWidth, bounds.outHeight), maxEdge);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static int sampleSize(int longestEdge, int maxEdge) {
        int sample = 1;
        if (maxEdge <= 0) {
            return sample;
        }
        while (sample < 32 && longestEdge / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        return sample;
    }

    @Override
    public Drawable getCurrent(final Calendar now) {
        loadHeaderImage();
        return mImage;
    }
}
