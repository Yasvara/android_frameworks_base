/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.DisplayMetrics;

/**
 * Placement math shared by YasvaraLayangService and the Shell LayangController (
 * §4.1, §6.4). Pure functions on display pixels; no state.
 *
 * @hide
 */
public final class LayangGeometry {
    private LayangGeometry() {}

    public static int dpToPx(float dp, int densityDpi) {
        return Math.round(dp * densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT);
    }

    /**
     * Size of a game window: the virtual screen (vLong x vShort, oriented by landscape) times
     * scale, clamped so it fits the display with margins.
     */
    public static void gameSize(int vLong, int vShort, boolean landscape, float scale,
            int displayW, int displayH, int margin, @NonNull int[] outWH) {
        final int vw = landscape ? vLong : vShort;
        final int vh = landscape ? vShort : vLong;
        float s = Layang.clampScale(scale);
        final float fit = Math.min((displayW - 2f * margin) / vw, (displayH - 2f * margin) / vh);
        s = Math.min(s, fit);
        outWH[0] = Math.round(vw * s);
        outWH[1] = Math.round(vh * s);
    }

    /** Scale of a game window of width w whose virtual width is vw. */
    public static float scaleOf(int w, int vw) {
        return vw > 0 ? (float) w / vw : 0f;
    }

    /** Default size of a non-game window: 60% of the display width by 55% of its height. */
    public static void defaultAppSize(int displayW, int displayH, @NonNull int[] outWH) {
        outWH[0] = Math.round(displayW * 0.60f);
        outWH[1] = Math.round(displayH * 0.55f);
    }

    /**
     * Density that keeps the smallest width of a w x h window at or above
     * {@link Layang#READABLE_MIN_SW_DP}, never above the display density (D10).
     */
    public static int readableDensity(int w, int h, int displayDpi) {
        final int minSide = Math.min(w, h);
        final int dpi = minSide * DisplayMetrics.DENSITY_DEFAULT / Layang.READABLE_MIN_SW_DP;
        return Math.max(DisplayMetrics.DENSITY_LOW, Math.min(displayDpi, dpi));
    }

    /**
     * Places a w x h window. With a remembered centre (cx, cy in 0..1, negative when none) it
     * goes there; otherwise top-right on a portrait display and bottom-left on a landscape one
     * (§6.4). The result is kept inside the display with margins.
     */
    public static void place(int w, int h, float cx, float cy, int displayW, int displayH,
            int margin, int topInset, @NonNull Rect out) {
        int left;
        int top;
        if (cx >= 0 && cy >= 0 && cx <= 1 && cy <= 1) {
            left = Math.round(cx * displayW - w / 2f);
            top = Math.round(cy * displayH - h / 2f);
        } else if (displayH >= displayW) {
            left = displayW - margin - w;
            top = Math.max(margin, topInset);
        } else {
            left = margin;
            top = displayH - margin - h;
        }
        out.set(left, top, left + w, top + h);
        clamp(out, displayW, displayH, margin, topInset);
    }

    /**
     * Shrinks r around its centre, keeping its aspect, until it fits the display with margins
     * and below topInset, then moves it inside.
     */
    public static void fit(@NonNull Rect r, int displayW, int displayH, int margin,
            int topInset) {
        final int maxW = displayW - 2 * margin;
        final int maxH = displayH - margin - Math.max(margin, topInset);
        final float s = Math.min(1f, Math.min((float) maxW / Math.max(1, r.width()),
                (float) maxH / Math.max(1, r.height())));
        if (s < 1f) {
            final int w = Math.round(r.width() * s);
            final int h = Math.round(r.height() * s);
            final int l = Math.round(r.exactCenterX() - w / 2f);
            final int t = Math.round(r.exactCenterY() - h / 2f);
            r.set(l, t, l + w, t + h);
        }
        clamp(r, displayW, displayH, margin, topInset);
    }

    /**
     * Moves r inside the display with margins; shrinks nothing. topInset already holds the room
     * for the pill, so the top edge gets no extra margin.
     */
    public static void clamp(@NonNull Rect r, int displayW, int displayH, int margin,
            int topInset) {
        final int minTop = Math.min(Math.max(margin, topInset),
                Math.max(0, displayH - r.height()));
        int dx = 0;
        int dy = 0;
        if (r.right > displayW - margin) dx = displayW - margin - r.right;
        if (r.left + dx < margin) dx = margin - r.left;
        if (r.bottom > displayH - margin) dy = displayH - margin - r.bottom;
        if (r.top + dy < minTop) dy = minTop - r.top;
        r.offset(dx, dy);
    }

    /**
     * Mini window (§6.4): {@link Layang#MINI_WIDTH_DP} wide along the window's short side
     * keeping the aspect of from, in the display corner nearest to from's centre.
     */
    public static void mini(@NonNull Rect from, int displayW, int displayH, int densityDpi,
            int topInset, @NonNull Rect out) {
        final int margin = dpToPx(Layang.MARGIN_DP, densityDpi);
        final int miniW = dpToPx(Layang.MINI_WIDTH_DP, densityDpi);
        final float aspect = from.width() > 0 ? (float) from.height() / from.width() : 1f;
        final int w;
        final int h;
        if (aspect >= 1f) {
            w = miniW;
            h = Math.round(miniW * aspect);
        } else {
            h = miniW;
            w = Math.round(miniW / aspect);
        }
        final boolean right = from.centerX() >= displayW / 2;
        final boolean bottom = from.centerY() >= displayH / 2;
        final int left = right ? displayW - margin - w : margin;
        final int top = bottom ? displayH - margin - h : Math.max(margin, topInset);
        out.set(left, top, left + w, top + h);
        clamp(out, displayW, displayH, margin, topInset);
    }

    /** Normalised centre of r on the display, written as {cx, cy}. */
    public static void centre(@NonNull Rect r, int displayW, int displayH,
            @NonNull float[] outCxCy) {
        outCxCy[0] = displayW > 0 ? r.exactCenterX() / displayW : 0.5f;
        outCxCy[1] = displayH > 0 ? r.exactCenterY() / displayH : 0.5f;
    }
}
