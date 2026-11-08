/*
 * Copyright (C) 2026 The DerpFest Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.policy;

import android.view.animation.Interpolator;

/**
 * Closed-form damped-spring interpolator (underdamped => small bounce at the end).
 * Input t is normalized 0..1 over the animation duration; the spring is guaranteed
 * to have settled at t == 1.
 */
public class SpringInterpolator implements Interpolator {
    private final float mZeta;
    private final float mOmega;   // natural frequency in 1/normalizedTime
    private final float mOmegaD;  // damped frequency
    private final float mNorm;

    /** @param dampingRatio 1 = no bounce, ~0.75 = ColorOS-like soft bounce
     *  @param omega        stiffness; 14-18 settles inside the duration */
    public SpringInterpolator(float dampingRatio, float omega) {
        mZeta = Math.min(dampingRatio, 0.999f);
        mOmega = omega;
        mOmegaD = omega * (float) Math.sqrt(1f - mZeta * mZeta);
        mNorm = raw(1f);
    }

    private float raw(float t) {
        double e = Math.exp(-mZeta * mOmega * t);
        double c = Math.cos(mOmegaD * t);
        double s = (mZeta * mOmega / mOmegaD) * Math.sin(mOmegaD * t);
        return (float) (1.0 - e * (c + s));
    }

    @Override
    public float getInterpolation(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return raw(t) / mNorm;
    }
}
