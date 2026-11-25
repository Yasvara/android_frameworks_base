/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.yasvara.ILayangManager;
import android.app.yasvara.Layang;
import android.app.yasvara.LayangGeometry;
import android.app.yasvara.LayangTaskState;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.ScreenCaptureInternal;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;

import java.io.OutputStream;
import java.util.ArrayList;

/**
 * The window menu opened by tapping the pill: a compact Material You card with
 * Fullscreen · Mini · Ghost · Lock · Minimize · Close, then App sound · Rotate · Reflow ·
 * Always open floating · Screenshot window · Quick tuning · Layang settings, plus window shapes
 * and an expandable inline Quick Tuning drawer (live FPS limit, live resolution scaling,
 * bubble size with live screen preview, and ghost opacity with live transparency preview).
 * Anchored under the pill and owned by Shell; a touch outside dismisses it smoothly.
 */
final class LayangMenu {
    private static final String TAG = "LayangMenu";
    private static final int WIDTH_DP = 304;
    private static final String ACTION_LARAS_LAYANG = "org.yasvara.laras.action.LAYANG";
    private static final int[] FPS_OPTIONS = {0, 30, 45, 60, 90, 120};
    private static final float[] RES_OPTIONS = {1.0f, 0.85f, 0.75f, 0.60f};
    private static final String[] RES_LABELS = {null, "85%", "75%", "60%"};

    private final LayangController mController;
    private final LayangWindow mWindow;
    private final Context mContext;
    private final Runnable mOnDismissed;
    private final SurfaceControlViewHost mHost;
    private final SurfaceControl mContainer;

    private final Rect mAnchor = new Rect();
    @Nullable private View mRoot;
    @Nullable private WindowManager.LayoutParams mLayoutParams;
    @Nullable private LinearLayout mTuneDrawer;
    @Nullable private ImageView mTuneButton;
    @Nullable private View mRelaunchBtn;
    @Nullable private ValueAnimator mAnim;
    private final ArrayList<TextView> mFpsChips = new ArrayList<>();
    private final ArrayList<TextView> mResChips = new ArrayList<>();
    @Nullable private Palette mPalette;
    private int mDpi;
    private int mWidthPx;
    private int mHeightPx;
    private int mLeftPx;
    private int mTopPx;
    private int mSelectedFps;
    private float mSelectedRes = 1.0f;
    private boolean mTuneExpanded;
    private boolean mDismissing;
    private boolean mReleased;

    LayangMenu(@NonNull LayangController controller, @NonNull LayangWindow window,
            @NonNull Display display, @NonNull Runnable onDismissed) {
        mController = controller;
        mWindow = window;
        mContext = controller.getContext();
        mOnDismissed = onDismissed;
        mHost = new SurfaceControlViewHost(mContext, display,
                (android.window.InputTransferToken) null, "LayangMenu");
        final SurfaceControl.Builder b = new SurfaceControl.Builder()
                .setName("Layang menu #" + window.getTaskId())
                .setContainerLayer()
                .setCallsite("LayangMenu");
        controller.getRootTdaOrganizer().attachToDisplayArea(window.getTaskInfo().displayId, b);
        mContainer = b.build();
    }

    void show(@NonNull Rect anchor) {
        mAnchor.set(anchor);
        final DisplayLayout dl = mController.displayLayout(mWindow.getTaskInfo().displayId);
        mDpi = dl.densityDpi();
        mPalette = new Palette();
        mRoot = buildContent(mPalette, mDpi);
        mWidthPx = LayangGeometry.dpToPx(WIDTH_DP, mDpi);
        measureAndPosition(dl, false /* relayout */);

        mLayoutParams = new WindowManager.LayoutParams(mWidthPx, mHeightPx,
                TYPE_APPLICATION,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mLayoutParams.setTitle("LayangMenu #" + mWindow.getTaskId());
        mLayoutParams.setTrustedOverlay();
        mHost.setView(mRoot, mLayoutParams);

        final SurfaceControl sc = mHost.getSurfacePackage().getSurfaceControl();
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        t.reparent(sc, mContainer).show(sc)
                .setLayer(mContainer, Integer.MAX_VALUE)
                .show(mContainer);
        applyMenuTransform(t, 0.86f, 0f);
        t.apply();
        mController.getTransactionPool().release(t);

        animatePopIn();
    }

    private void measureAndPosition(@NonNull DisplayLayout dl, boolean relayout) {
        if (mRoot == null) {
            return;
        }
        mRoot.measure(View.MeasureSpec.makeMeasureSpec(mWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dl.height(), View.MeasureSpec.AT_MOST));
        mHeightPx = mRoot.getMeasuredHeight();
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, mDpi);
        int left = mAnchor.centerX() - mWidthPx / 2;
        int top = mAnchor.top + LayangGeometry.dpToPx(4, mDpi);
        left = Math.max(margin, Math.min(dl.width() - margin - mWidthPx, left));
        top = Math.max(dl.stableInsets().top + margin,
                Math.min(dl.height() - margin - mHeightPx, top));
        mLeftPx = left;
        mTopPx = top;
        if (relayout && mLayoutParams != null && !mReleased) {
            mLayoutParams.width = mWidthPx;
            mLayoutParams.height = mHeightPx;
            mHost.relayout(mLayoutParams);
            if (mContainer.isValid()) {
                final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
                applyMenuTransform(t, 1f, 1f);
                t.apply();
                mController.getTransactionPool().release(t);
            }
        }
    }

    /** Scales around the top-centre pivot under the pill for a natural spring pop-in. */
    private void applyMenuTransform(@NonNull SurfaceControl.Transaction t, float scale,
            float alpha) {
        if (!mContainer.isValid()) {
            return;
        }
        final float px = mLeftPx + (mWidthPx * (1f - scale)) * 0.5f;
        final float py = mTopPx;
        t.setPosition(mContainer, px, py)
                .setScale(mContainer, scale, scale)
                .setAlpha(mContainer, Math.max(0f, Math.min(1f, alpha)));
    }

    private void animatePopIn() {
        if (mAnim != null) {
            mAnim.cancel();
        }
        final OvershootInterpolator scaleInterp = new OvershootInterpolator(0.85f);
        final DecelerateInterpolator alphaInterp = new DecelerateInterpolator(2.0f);
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(190L);
        mAnim = va;
        va.addUpdateListener(a -> {
            if (mReleased || mDismissing || !mContainer.isValid()) {
                return;
            }
            final float frac = a.getAnimatedFraction();
            final float s = 0.86f + 0.14f * scaleInterp.getInterpolation(frac);
            final float alpha = alphaInterp.getInterpolation(Math.min(1f, frac * 1.5f));
            final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
            applyMenuTransform(t, s, alpha);
            t.setFrameTimelineVsync(mController.getChoreographer().getVsyncId());
            t.apply();
            mController.getTransactionPool().release(t);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAnim == animation) {
                    mAnim = null;
                }
            }
        });
        va.start();
    }

    /** Smooth shrink-out dismiss; clears any active live previews immediately. */
    void dismiss() {
        if (mDismissing || mReleased) {
            return;
        }
        mDismissing = true;
        mWindow.clearGhostPreview();
        mWindow.hideMiniSizePreview();
        if (mAnim != null) {
            mAnim.cancel();
            mAnim = null;
        }
        mOnDismissed.run();
        if (!mContainer.isValid()) {
            releaseResources();
            return;
        }
        final DecelerateInterpolator interp = new DecelerateInterpolator(1.8f);
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(110L);
        mAnim = va;
        va.addUpdateListener(a -> {
            if (mReleased || !mContainer.isValid()) {
                return;
            }
            final float f = interp.getInterpolation(a.getAnimatedFraction());
            final float s = 1f - 0.10f * f;
            final float alpha = 1f - f;
            final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
            applyMenuTransform(t, s, alpha);
            t.setFrameTimelineVsync(mController.getChoreographer().getVsyncId());
            t.apply();
            mController.getTransactionPool().release(t);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mAnim == animation) {
                    mAnim = null;
                }
                releaseResources();
            }
        });
        va.start();
    }

    /** Immediate teardown without animation (called when the owning window is destroyed). */
    void dismissImmediate() {
        if (mReleased) {
            return;
        }
        final boolean notify = !mDismissing;
        mDismissing = true;
        mWindow.clearGhostPreview();
        mWindow.hideMiniSizePreview();
        if (mAnim != null) {
            mAnim.cancel();
            mAnim = null;
        }
        if (notify) {
            mOnDismissed.run();
        }
        releaseResources();
    }

    private void releaseResources() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        mHost.release();
        if (mContainer.isValid()) {
            final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
            t.remove(mContainer).apply();
            mController.getTransactionPool().release(t);
            mContainer.release();
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Colours of the menu for the current night mode, from the dynamic palette. */
    private final class Palette {
        final int surface;
        final int fg;
        final int subFg;
        final int tonal;
        final int activeBg;
        final int activeFg;
        final int accent;
        final int divider;
        final int danger;

        Palette() {
            final boolean night = (mContext.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            surface = color(night ? android.R.color.system_neutral1_900
                    : android.R.color.system_neutral1_10);
            fg = color(night ? android.R.color.system_neutral1_100
                    : android.R.color.system_neutral1_900);
            subFg = color(night ? android.R.color.system_neutral2_300
                    : android.R.color.system_neutral2_600);
            tonal = color(night ? android.R.color.system_neutral2_800
                    : android.R.color.system_neutral2_100);
            activeBg = color(night ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_100);
            activeFg = color(android.R.color.system_accent1_900);
            accent = color(night ? android.R.color.system_accent1_300
                    : android.R.color.system_accent1_600);
            divider = night ? 0x22FFFFFF : 0x1A000000;
            danger = night ? 0xFFF2B8B5 : 0xFFB3261E;
        }

        private int color(int res) {
            return mContext.getColor(res);
        }
    }

    /**
     * Compact rows (§6.2): window actions, app options + inline Quick Tuning + Layang settings,
     * aspect shapes, and the expandable Quick Tuning drawer with live previews.
     */
    private View buildContent(Palette c, int dpi) {
        final LinearLayout root = new LinearLayout(mContext) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    dismiss();
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(c.surface, LayangGeometry.dpToPx(28, dpi)));
        final int pad = LayangGeometry.dpToPx(8, dpi);
        root.setPadding(pad, pad, pad, pad);

        final int taskId = mWindow.getTaskId();
        final LayangTaskState s = mWindow.getState();
        final boolean ghost = s != null && s.isGhost();
        final boolean locked = s != null && s.hasFlag(LayangTaskState.FLAG_LOCKED);

        // Row 1: Primary window actions.
        final LinearLayout actions = row();
        addIcon(actions, c, dpi, 46, R.drawable.ic_layang_fullscreen,
                R.string.layang_menu_fullscreen, false, c.fg, true,
                () -> mController.toFullscreen(taskId));
        addIcon(actions, c, dpi, 46, R.drawable.ic_layang_mini, R.string.layang_menu_mini,
                false, c.fg, true, () -> mController.setMode(taskId, LayangTaskState.MODE_MINI));
        addIcon(actions, c, dpi, 46, R.drawable.ic_layang_ghost,
                ghost ? R.string.layang_menu_unghost : R.string.layang_menu_ghost, ghost, c.fg,
                true, () -> mController.setFlags(taskId, ghost ? 0 : LayangTaskState.FLAG_GHOST,
                        LayangTaskState.FLAG_GHOST));
        addIcon(actions, c, dpi, 46, R.drawable.ic_layang_lock,
                locked ? R.string.layang_menu_unlock : R.string.layang_menu_lock, locked, c.fg,
                true, () -> mController.setFlags(taskId, locked ? 0 : LayangTaskState.FLAG_LOCKED,
                        LayangTaskState.FLAG_LOCKED));
        addIcon(actions, c, dpi, 46, R.drawable.ic_layang_minimize,
                R.string.layang_menu_minimize, false, c.fg, true,
                () -> mController.minimize(taskId));
        addIcon(actions, c, dpi, 46, R.drawable.ic_layang_close, R.string.layang_menu_close,
                false, c.danger, true, () -> mController.close(taskId));
        root.addView(actions, matchWidth());

        // Row 2: App options + inline Quick Tuning toggle + direct Layang Settings.
        final LinearLayout more = row();
        more.setPadding(0, LayangGeometry.dpToPx(4, dpi), 0, 0);
        addIcon(more, c, dpi, 40, R.drawable.ic_layang_volume, R.string.layang_menu_volume,
                false, c.fg, true, this::openVolumePanel);
        final int fixed = mWindow.fixedOrientation();
        if (fixed == Configuration.ORIENTATION_UNDEFINED) {
            addIcon(more, c, dpi, 40, R.drawable.ic_layang_rotate, R.string.layang_menu_rotate,
                    false, c.fg, true, () -> mController.rotate(taskId));
        }
        if (s != null && !s.isGame()) {
            final boolean reflow = s.hasFlag(LayangTaskState.FLAG_REFLOW);
            addIcon(more, c, dpi, 40, R.drawable.ic_layang_reflow, R.string.layang_menu_reflow,
                    reflow, c.fg, true, () -> setReflow(!reflow));
        }
        final ImageView always = addIcon(more, c, dpi, 40, R.drawable.ic_layang_pin,
                R.string.layang_menu_always, false, c.fg, true, this::toggleAlwaysFloating);
        addIcon(more, c, dpi, 40, R.drawable.ic_layang_screenshot,
                R.string.layang_menu_screenshot, false, c.fg, true, this::screenshot);
        mTuneButton = addIcon(more, c, dpi, 40, R.drawable.ic_layang_tune,
                R.string.layang_menu_tune, false, c.fg, false /* keep open */,
                this::toggleTuneDrawer);
        addIcon(more, c, dpi, 40, R.drawable.ic_layang_settings, R.string.layang_menu_settings,
                false, c.fg, true, this::openLayangSettings);
        root.addView(more, matchWidth());

        // Row 3: Window shapes (when resizable).
        if (s != null && (!s.isGame() || s.hasFlag(LayangTaskState.FLAG_ASPECT_UNLOCKED))) {
            addRatios(root, c, dpi, fixed);
        }

        // Expandable inline Quick Tuning drawer (FPS, Resolution, Bubble Size, Ghost Opacity).
        mTuneDrawer = buildTuneDrawer(c, dpi, s != null && s.isGame());
        mTuneDrawer.setVisibility(View.GONE);
        root.addView(mTuneDrawer, matchWidth());

        loadAppRules(always, c, dpi);
        return root;
    }

    private LinearLayout buildTuneDrawer(Palette c, int dpi, boolean isGame) {
        final LinearLayout box = new LinearLayout(mContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(LayangGeometry.dpToPx(6, dpi), LayangGeometry.dpToPx(8, dpi),
                LayangGeometry.dpToPx(6, dpi), LayangGeometry.dpToPx(2, dpi));

        // Subtle top divider.
        final View divider = new View(mContext);
        divider.setBackgroundColor(c.divider);
        final LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, LayangGeometry.dpToPx(1, dpi)));
        dlp.bottomMargin = LayangGeometry.dpToPx(8, dpi);
        box.addView(divider, dlp);

        // 1. Live FPS Limit chips (Auto · 30 · 45 · 60 · 90 · 120).
        box.addView(sectionLabel(mContext.getString(R.string.layang_tune_fps), c, dpi),
                matchWidth());
        final LinearLayout fpsRow = row();
        fpsRow.setPadding(0, LayangGeometry.dpToPx(4, dpi), 0, LayangGeometry.dpToPx(8, dpi));
        final int gap = LayangGeometry.dpToPx(3, dpi);
        mFpsChips.clear();
        for (int fps : FPS_OPTIONS) {
            final String text = fps == 0
                    ? mContext.getString(R.string.layang_tune_fps_auto)
                    : String.valueOf(fps);
            final TextView chip = makeChip(text, fps == mSelectedFps, c, dpi);
            chip.setTag(fps);
            chip.setOnClickListener(v -> {
                mWindow.tick();
                selectFps(fps);
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LayangGeometry.dpToPx(28, dpi), 1f);
            lp.setMarginStart(gap);
            lp.setMarginEnd(gap);
            fpsRow.addView(chip, lp);
            mFpsChips.add(chip);
        }
        box.addView(fpsRow, matchWidth());

        // 2. Live Resolution Scale chips (Native · 85% · 75% · 60%) + game relaunch button.
        final LinearLayout resHeader = row();
        final TextView resTitle = sectionLabel(mContext.getString(R.string.layang_tune_res), c, dpi);
        resHeader.addView(resTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (isGame) {
            final TextView relaunch = new TextView(mContext);
            relaunch.setText(R.string.layang_tune_relaunch_hint);
            relaunch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            relaunch.setTextColor(c.activeFg);
            relaunch.setBackground(rounded(c.activeBg, LayangGeometry.dpToPx(12, dpi)));
            final int hp = LayangGeometry.dpToPx(8, dpi);
            final int vp = LayangGeometry.dpToPx(2, dpi);
            relaunch.setPadding(hp, vp, hp, vp);
            relaunch.setContentDescription(mContext.getString(R.string.layang_menu_relaunch));
            relaunch.setVisibility(View.GONE);
            relaunch.setOnClickListener(v -> {
                final String pkg = packageName();
                if (pkg != null) {
                    dismiss();
                    mController.relaunchFloating(mWindow.getTaskId(), pkg,
                            mWindow.getTaskInfo().userId);
                }
            });
            mRelaunchBtn = relaunch;
            resHeader.addView(relaunch);
        }
        box.addView(resHeader, matchWidth());

        final LinearLayout resRow = row();
        resRow.setPadding(0, LayangGeometry.dpToPx(4, dpi), 0, LayangGeometry.dpToPx(8, dpi));
        mResChips.clear();
        for (int i = 0; i < RES_OPTIONS.length; i++) {
            final float rs = RES_OPTIONS[i];
            final String label = RES_LABELS[i] != null ? RES_LABELS[i]
                    : mContext.getString(R.string.layang_tune_res_native);
            final TextView chip = makeChip(label, Math.abs(rs - mSelectedRes) < 0.02f, c, dpi);
            chip.setTag(rs);
            chip.setOnClickListener(v -> {
                mWindow.tick();
                selectResolution(rs);
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LayangGeometry.dpToPx(28, dpi), 1f);
            lp.setMarginStart(gap);
            lp.setMarginEnd(gap);
            resRow.addView(chip, lp);
            mResChips.add(chip);
        }
        box.addView(resRow, matchWidth());

        // 3. Bubble / Mini size slider (72..140 dp) with live on-screen corner preview.
        final int curMiniDp = mController.getMiniWidthDp();
        final TextView miniValue = valueLabel(curMiniDp + " dp", c);
        box.addView(sliderHeader(mContext.getString(R.string.layang_tune_mini_size), miniValue,
                c, dpi), matchWidth());
        final SeekBar miniSeek = makeSeekBar(c, Layang.MINI_WIDTH_DP_MAX - Layang.MINI_WIDTH_DP_MIN,
                curMiniDp - Layang.MINI_WIDTH_DP_MIN);
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final int dp = Layang.MINI_WIDTH_DP_MIN + progress;
                miniValue.setText(dp + " dp");
                if (fromUser) {
                    mWindow.showMiniSizePreview(dp);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mWindow.showMiniSizePreview(Layang.MINI_WIDTH_DP_MIN + seekBar.getProgress());
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                final int dp = Layang.MINI_WIDTH_DP_MIN + seekBar.getProgress();
                mWindow.hideMiniSizePreview();
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Layang.KEY_MINI_WIDTH_DP, dp, UserHandle.USER_CURRENT);
            }
        });
        box.addView(miniSeek, sliderParams(dpi));

        // 4. Ghost opacity slider (20..80%) with live transparency preview on the window.
        final int curGhostPct = Math.round(mController.getGhostAlpha() * 100f);
        final TextView ghostValue = valueLabel(curGhostPct + "%", c);
        box.addView(sliderHeader(mContext.getString(R.string.layang_tune_ghost), ghostValue,
                c, dpi), matchWidth());
        final SeekBar ghostSeek = makeSeekBar(c, 60, Math.max(0, Math.min(60, curGhostPct - 20)));
        ghostSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final int pct = 20 + progress;
                ghostValue.setText(pct + "%");
                if (fromUser) {
                    mWindow.previewGhostAlpha(pct / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mWindow.previewGhostAlpha((20 + seekBar.getProgress()) / 100f);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                final int pct = 20 + seekBar.getProgress();
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Layang.KEY_GHOST_ALPHA, pct, UserHandle.USER_CURRENT);
                mWindow.clearGhostPreview();
            }
        });
        box.addView(ghostSeek, sliderParams(dpi));

        // 5. Compact footer: App Info + Full Layang Settings.
        final LinearLayout footer = row();
        footer.setPadding(0, LayangGeometry.dpToPx(6, dpi), 0, 0);
        footer.addView(makeFooterButton(mContext.getString(R.string.layang_menu_app_info), c, dpi,
                () -> {
                    dismiss();
                    openAppInfo();
                }), footerButtonParams(dpi, false));
        footer.addView(makeFooterButton(mContext.getString(R.string.layang_menu_settings), c, dpi,
                () -> {
                    dismiss();
                    openLayangSettings();
                }), footerButtonParams(dpi, true));
        box.addView(footer, matchWidth());

        return box;
    }

    private void toggleTuneDrawer() {
        if (mTuneDrawer == null || mTuneButton == null || mPalette == null) {
            return;
        }
        mTuneExpanded = !mTuneExpanded;
        mTuneDrawer.setVisibility(mTuneExpanded ? View.VISIBLE : View.GONE);
        setActive(mTuneButton, mPalette, mDpi, mTuneExpanded, mPalette.fg);
        if (!mTuneExpanded) {
            mWindow.clearGhostPreview();
            mWindow.hideMiniSizePreview();
        }
        final DisplayLayout dl = mController.displayLayout(mWindow.getTaskInfo().displayId);
        measureAndPosition(dl, true /* relayout */);
    }

    private void selectFps(int fps) {
        mSelectedFps = fps;
        updateFpsChipStyles();
        final ILayangManager service = mController.getService();
        final String pkg = packageName();
        final int userId = mWindow.getTaskInfo().userId;
        if (service == null || pkg == null) {
            return;
        }
        mController.getBgExecutor().execute(() -> {
            try {
                final Bundle b = new Bundle();
                b.putInt(Layang.RULE_FPS, fps);
                service.setRules(pkg, userId, b);
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "setRules fps failed", e);
            }
        });
    }

    private void selectResolution(float scale) {
        mSelectedRes = scale;
        updateResChipStyles();
        if (mRelaunchBtn != null) {
            mRelaunchBtn.setVisibility(scale < 0.99f ? View.VISIBLE : View.GONE);
        }
        final ILayangManager service = mController.getService();
        final String pkg = packageName();
        final int userId = mWindow.getTaskInfo().userId;
        if (service == null || pkg == null) {
            return;
        }
        mController.getBgExecutor().execute(() -> {
            try {
                final Bundle b = new Bundle();
                b.putFloat(Layang.RULE_RES_SCALE, scale);
                service.setRules(pkg, userId, b);
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "setRules resScale failed", e);
            }
        });
    }

    private void updateFpsChipStyles() {
        if (mPalette == null) return;
        for (int i = 0; i < mFpsChips.size(); i++) {
            final TextView chip = mFpsChips.get(i);
            final int val = (Integer) chip.getTag();
            styleChip(chip, val == mSelectedFps, mPalette, mDpi);
        }
    }

    private void updateResChipStyles() {
        if (mPalette == null) return;
        for (int i = 0; i < mResChips.size(); i++) {
            final TextView chip = mResChips.get(i);
            final float val = (Float) chip.getTag();
            styleChip(chip, Math.abs(val - mSelectedRes) < 0.02f, mPalette, mDpi);
        }
    }

    private TextView sectionLabel(String text, Palette c, int dpi) {
        final TextView tv = new TextView(mContext);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(c.subFg);
        tv.setPadding(LayangGeometry.dpToPx(2, dpi), 0, 0, 0);
        return tv;
    }

    private TextView valueLabel(String text, Palette c) {
        final TextView tv = new TextView(mContext);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(c.accent);
        return tv;
    }

    private LinearLayout sliderHeader(String title, TextView valueView, Palette c, int dpi) {
        final LinearLayout h = row();
        h.setPadding(0, LayangGeometry.dpToPx(2, dpi), 0, 0);
        h.addView(sectionLabel(title, c, dpi), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        h.addView(valueView);
        return h;
    }

    private SeekBar makeSeekBar(Palette c, int max, int progress) {
        final SeekBar sb = new SeekBar(mContext);
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setThumbTintList(ColorStateList.valueOf(c.accent));
        sb.setProgressTintList(ColorStateList.valueOf(c.accent));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(c.tonal));
        return sb;
    }

    private static LinearLayout.LayoutParams sliderParams(int dpi) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = LayangGeometry.dpToPx(4, dpi);
        return lp;
    }

    private TextView makeChip(String text, boolean selected, Palette c, int dpi) {
        final TextView v = new TextView(mContext);
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        v.setSingleLine(true);
        styleChip(v, selected, c, dpi);
        return v;
    }

    private static void styleChip(TextView v, boolean selected, Palette c, int dpi) {
        v.setTextColor(selected ? c.activeFg : c.fg);
        v.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        v.setBackground(rounded(selected ? c.activeBg : c.tonal,
                LayangGeometry.dpToPx(14, dpi)));
    }

    private TextView makeFooterButton(String text, Palette c, int dpi, Runnable onClick) {
        final TextView v = new TextView(mContext);
        v.setText(text);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTextColor(c.fg);
        v.setBackground(rounded(c.tonal, LayangGeometry.dpToPx(16, dpi)));
        v.setOnClickListener(x -> onClick.run());
        return v;
    }

    private static LinearLayout.LayoutParams footerButtonParams(int dpi, boolean startMargin) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LayangGeometry.dpToPx(32, dpi), 1f);
        if (startMargin) {
            lp.setMarginStart(LayangGeometry.dpToPx(6, dpi));
        }
        return lp;
    }

    /**
     * Window shapes, tallest to widest; the current one is filled. An app with a fixed
     * orientation only gets the shapes of that orientation.
     */
    private void addRatios(LinearLayout root, Palette c, int dpi, int fixed) {
        final DisplayLayout dl = mController.displayLayout(mWindow.getTaskInfo().displayId);
        final float display = (float) Math.min(dl.width(), dl.height())
                / Math.max(dl.width(), dl.height());
        final String[] labels = {"9:20", "3:4", "1:1", "16:9"};
        final float[] ratios = {display, 0.75f, 1f, 16f / 9f};
        final Rect b = mWindow.getBounds();
        final float now = (float) b.width() / Math.max(1, b.height());
        final LinearLayout row = row();
        row.setPadding(0, LayangGeometry.dpToPx(6, dpi), 0, 0);
        final int gap = LayangGeometry.dpToPx(4, dpi);
        for (int i = 0; i < labels.length; i++) {
            final float ratio = ratios[i];
            if ((fixed == Configuration.ORIENTATION_PORTRAIT && ratio > 1f)
                    || (fixed == Configuration.ORIENTATION_LANDSCAPE && ratio < 1f)) {
                continue;
            }
            final boolean on = Math.abs(now - ratio) <= 0.02f * ratio;
            final TextView v = new TextView(mContext);
            v.setText(labels[i]);
            v.setGravity(Gravity.CENTER);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            v.setTextColor(on ? c.activeFg : c.fg);
            v.setBackground(rounded(on ? c.activeBg : c.tonal,
                    LayangGeometry.dpToPx(16, dpi)));
            v.setContentDescription(mContext.getString(R.string.layang_menu_ratio, labels[i]));
            v.setOnClickListener(x -> {
                dismiss();
                mController.reshape(mWindow.getTaskId(), ratio);
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LayangGeometry.dpToPx(32, dpi), 1f);
            lp.setMarginStart(gap);
            lp.setMarginEnd(gap);
            row.addView(v, lp);
        }
        root.addView(row, matchWidth());
    }

    /** Flips this window between scaling and reflow and remembers it for the app. */
    private void setReflow(boolean reflow) {
        mController.setReflow(mWindow.getTaskId(), reflow);
        Toast.makeText(mContext, reflow ? R.string.layang_reflow_on : R.string.layang_reflow_off,
                Toast.LENGTH_SHORT).show();
        final ILayangManager service = mController.getService();
        final String pkg = packageName();
        final int userId = mWindow.getTaskInfo().userId;
        if (service == null || pkg == null) {
            return;
        }
        mController.getBgExecutor().execute(() -> {
            try {
                final Bundle b = new Bundle();
                b.putInt(Layang.RULE_READABLE, reflow ? 1 : 0);
                service.setRules(pkg, userId, b);
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "setRules failed", e);
            }
        });
    }

    private LinearLayout row() {
        final LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static GradientDrawable rounded(int color, float radius) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Fills the button's circle when on, so a toggle reads at a glance. */
    private void setActive(ImageView v, Palette c, int dpi, boolean on, int tint) {
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(on ? c.activeBg : 0);
        final TypedValue ripple = new TypedValue();
        if (mContext.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,
                ripple, true)) {
            v.setForeground(mContext.getDrawable(ripple.resourceId));
        }
        v.setBackground(circle);
        v.setImageTintList(ColorStateList.valueOf(on ? c.activeFg : tint));
    }

    /** One icon button; the row shares its width evenly when weighted. */
    private ImageView addIcon(LinearLayout row, Palette c, int dpi, int sizeDp, int icon,
            int label, boolean on, int tint, boolean dismissOnClick, Runnable action) {
        final ImageView v = new ImageView(mContext);
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(mContext.getString(label));
        setActive(v, c, dpi, on, tint);
        v.setOnClickListener(x -> {
            if (dismissOnClick) {
                dismiss();
            }
            action.run();
        });
        v.setOnLongClickListener(x -> {
            Toast.makeText(mContext, label, Toast.LENGTH_SHORT).show();
            return true;
        });
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LayangGeometry.dpToPx(sizeDp, dpi), 1f);
        row.addView(v, lp);
        return v;
    }

    /** Loads Always Floating, FPS cap and Resolution scale rules off the main thread. */
    private void loadAppRules(ImageView alwaysBtn, Palette c, int dpi) {
        final ILayangManager service = mController.getService();
        final String pkg = packageName();
        final int userId = mWindow.getTaskInfo().userId;
        if (service == null || pkg == null) {
            return;
        }
        mController.getBgExecutor().execute(() -> {
            try {
                final Bundle rules = service.getRules(pkg, userId);
                final boolean always = rules.getBoolean(Layang.RULE_ALWAYS);
                final int fps = rules.getInt(Layang.RULE_FPS, 0);
                final float res = rules.getFloat(Layang.RULE_RES_SCALE, 1.0f);
                mController.getMainExecutor().execute(() -> {
                    if (mDismissing || mReleased) {
                        return;
                    }
                    if (always) {
                        setActive(alwaysBtn, c, dpi, true, c.fg);
                    }
                    mSelectedFps = fps;
                    mSelectedRes = res > 0f ? res : 1.0f;
                    updateFpsChipStyles();
                    updateResChipStyles();
                    if (mRelaunchBtn != null) {
                        mRelaunchBtn.setVisibility(mSelectedRes < 0.99f ? View.VISIBLE : View.GONE);
                    }
                });
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "getRules failed", e);
            }
        });
    }

    /** per-app volume lives in the Volume Panel's "Apps" (Swara). */
    private void openVolumePanel() {
        mContext.sendBroadcastAsUser(
                new Intent("com.android.systemui.action.LAUNCH_VOLUME_PANEL_DIALOG")
                        .setPackage(mContext.getPackageName()),
                android.os.UserHandle.CURRENT);
    }

    private void toggleAlwaysFloating() {
        final ILayangManager service = mController.getService();
        final String pkg = packageName();
        final int userId = mWindow.getTaskInfo().userId;
        if (service == null || pkg == null) {
            return;
        }
        mController.getBgExecutor().execute(() -> {
            try {
                final boolean now = !service.getRules(pkg, userId)
                        .getBoolean(Layang.RULE_ALWAYS);
                final Bundle b = new Bundle();
                b.putBoolean(Layang.RULE_ALWAYS, now);
                service.setRules(pkg, userId, b);
                mController.getMainExecutor().execute(() -> Toast.makeText(mContext,
                        now ? R.string.layang_always_on : R.string.layang_always_off,
                        Toast.LENGTH_SHORT).show());
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "setRules failed", e);
            }
        });
    }

    /** Captures only the task's layers off the main thread and recycles bitmaps cleanly. */
    private void screenshot() {
        final SurfaceControl leash = mWindow.getLeash();
        if (!leash.isValid()) {
            Toast.makeText(mContext, R.string.layang_screenshot_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final Rect b = new Rect(mWindow.getBounds());
        final String pkg = packageName();
        final SurfaceControl leashCopy = new SurfaceControl(leash, "LayangMenu.screenshot");
        mController.getBgExecutor().execute(() -> {
            boolean ok = false;
            Bitmap hw = null;
            Bitmap bitmap = null;
            try {
                final ScreenCaptureInternal.LayerCaptureArgs args =
                        new ScreenCaptureInternal.LayerCaptureArgs.Builder(leashCopy)
                                .setSourceCrop(new Rect(0, 0, b.width(), b.height()))
                                .build();
                final ScreenCaptureInternal.ScreenshotHardwareBuffer buffer =
                        ScreenCaptureInternal.captureLayers(args);
                if (buffer != null) {
                    hw = buffer.asBitmap();
                    if (hw != null) {
                        bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                    }
                }
                if (bitmap != null) {
                    final android.content.ContentValues v = new android.content.ContentValues();
                    final String name = "Screenshot_" + new java.text.SimpleDateFormat(
                            "yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date())
                            + (pkg != null ? "_" + pkg : "") + ".png";
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Screenshots");
                    final Uri uri = mContext.getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri != null) {
                        try (OutputStream out = mContext.getContentResolver().openOutputStream(uri)) {
                            ok = out != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "screenshot failed", e);
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (hw != null) hw.recycle();
                leashCopy.release();
            }
            final boolean saved = ok;
            mController.getMainExecutor().execute(() -> Toast.makeText(mContext,
                    saved ? R.string.layang_screenshot_saved : R.string.layang_screenshot_failed,
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void openLayangSettings() {
        final Intent i = new Intent(ACTION_LARAS_LAYANG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            mContext.startActivityAsUser(i, ActivityOptions.makeBasic().toBundle(),
                    UserHandle.of(mWindow.getTaskInfo().userId));
        } catch (RuntimeException e) {
            Log.w(TAG, "openLayangSettings failed", e);
        }
    }

    private void openAppInfo() {
        final String pkg = packageName();
        if (pkg == null) {
            return;
        }
        final Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivityAsUser(i, ActivityOptions.makeBasic().toBundle(),
                    UserHandle.of(mWindow.getTaskInfo().userId));
        } catch (RuntimeException e) {
            Log.w(TAG, "app info failed", e);
        }
    }

    @Nullable
    private String packageName() {
        final android.app.ActivityManager.RunningTaskInfo ti = mWindow.getTaskInfo();
        if (ti.baseActivity != null) return ti.baseActivity.getPackageName();
        if (ti.realActivity != null) return ti.realActivity.getPackageName();
        return ti.baseIntent != null && ti.baseIntent.getComponent() != null
                ? ti.baseIntent.getComponent().getPackageName() : null;
    }
}
