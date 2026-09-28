/*
 * Copyright (C) 2026 The Yasvara Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Handler;
import android.os.PatternMatcher;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

/**
 * Publishes the colours used by a dynamic-colour boot animation.
 *
 * The boot animation reads persist.bootanim.color1..4 and mixes them through
 * the four channels of each frame. Keep them in sync with the system theme so
 * the next boot follows dark mode and the Material You accent:
 *
 *   color1  background     black (dark) / white (light)
 *   color2  foreground     white (dark) / black (light)
 *   color3  accent         system_accent1 tone 80 (dark) / 40 (light)
 *   color4  accent, soft   system_accent1 tone 30 (dark) / 90 (light)
 */
public final class BootAnimationColorService extends SystemService {
    private static final String TAG = "BootAnimationColor";
    private static final String PROP_PREFIX = "persist.bootanim.color";

    // Theme overlays reach system_server resources shortly after the broadcast.
    private static final long OVERLAY_SETTLE_MS = 2000;

    private final Handler mHandler = BackgroundThread.getHandler();
    private final Runnable mUpdate = this::update;

    public BootAnimationColorService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_BOOT_COMPLETED) {
            return;
        }
        final Context context = getContext();

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                    // Last chance before the next boot animation plays.
                    mHandler.removeCallbacks(mUpdate);
                    update();
                } else {
                    scheduleUpdate(0);
                }
            }
        }, filter, null, mHandler);

        final IntentFilter overlayFilter = new IntentFilter(Intent.ACTION_OVERLAY_CHANGED);
        overlayFilter.addDataScheme("package");
        overlayFilter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                scheduleUpdate(OVERLAY_SETTLE_MS);
            }
        }, overlayFilter, null, mHandler);

        scheduleUpdate(0);
    }

    private void scheduleUpdate(long delayMs) {
        mHandler.removeCallbacks(mUpdate);
        mHandler.postDelayed(mUpdate, delayMs);
    }

    private void update() {
        try {
            final Resources res = getContext().getResources();
            final boolean dark = res.getConfiguration().isNightModeActive();
            final int background = dark ? Color.BLACK : Color.WHITE;
            final int foreground = dark ? Color.WHITE : Color.BLACK;
            final int accent = res.getColor(dark
                    ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_600, null);
            final int accentSoft = res.getColor(dark
                    ? android.R.color.system_accent1_700
                    : android.R.color.system_accent1_100, null);
            setColor(1, background);
            setColor(2, foreground);
            setColor(3, accent);
            setColor(4, accentSoft);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to update boot animation colours", e);
        }
    }

    private static void setColor(int index, int color) {
        final String prop = PROP_PREFIX + index;
        // The property is typed as an int and parsed as 0xRRGGBB.
        final String value = Integer.toString(color & 0xFFFFFF);
        if (!value.equals(SystemProperties.get(prop))) {
            SystemProperties.set(prop, value);
        }
    }
}
