/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.tuner;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.GroupSectionDividerMixin;
import com.android.systemui.res.R;

/**
 * Featured theme-store entry. {@link GroupSectionDividerMixin} keeps the expressive settings
 * adapter from folding this row into the rounded switch group and replacing its card background.
 */
public class FeaturedThemePreference extends Preference implements GroupSectionDividerMixin {

    private final CharSequence mActionText;
    private final int mPreviewIcon1;
    private final int mPreviewIcon2;
    private final int mPreviewIcon3;
    private final int mPreviewIcon4;

    public FeaturedThemePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        // The expressive preference style sets app:layout, which wins over android:layout.
        setLayoutResource(R.layout.preference_icon_packs_card);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.FeaturedThemePreference);
        mActionText = a.getText(R.styleable.FeaturedThemePreference_featuredActionText);
        mPreviewIcon1 = a.getResourceId(R.styleable.FeaturedThemePreference_featuredPreviewIcon1, 0);
        mPreviewIcon2 = a.getResourceId(R.styleable.FeaturedThemePreference_featuredPreviewIcon2, 0);
        mPreviewIcon3 = a.getResourceId(R.styleable.FeaturedThemePreference_featuredPreviewIcon3, 0);
        mPreviewIcon4 = a.getResourceId(R.styleable.FeaturedThemePreference_featuredPreviewIcon4, 0);
        a.recycle();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView action = (TextView) holder.findViewById(R.id.featured_theme_action);
        if (action != null && mActionText != null) {
            action.setText(mActionText);
        }
        setPreviewIcon(holder, R.id.featured_theme_preview_1, mPreviewIcon1);
        setPreviewIcon(holder, R.id.featured_theme_preview_2, mPreviewIcon2);
        setPreviewIcon(holder, R.id.featured_theme_preview_3, mPreviewIcon3);
        setPreviewIcon(holder, R.id.featured_theme_preview_4, mPreviewIcon4);
    }

    private static void setPreviewIcon(PreferenceViewHolder holder, int viewId, int iconRes) {
        if (iconRes == 0) {
            return;
        }
        ImageView icon = (ImageView) holder.findViewById(viewId);
        if (icon != null) {
            icon.setImageResource(iconRes);
        }
    }
}
