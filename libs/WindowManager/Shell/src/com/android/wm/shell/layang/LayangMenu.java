/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.NonNull;
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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.ScreenCaptureInternal;

import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;

import java.io.OutputStream;

/**
 * The window menu opened by tapping the pill: a compact icon card with
 * Fullscreen · Mini · Ghost · Lock · Minimize · Close, then App sound · Rotate · Reflow ·
 * Always open floating · Screenshot window · App info, then the window shapes.
 * Anchored under the pill and owned by Shell; a touch outside dismisses it.
 */
final class LayangMenu {
    private static final String TAG = "LayangMenu";
    private static final int WIDTH_DP = 280;

    private final LayangController mController;
    private final LayangWindow mWindow;
    private final Context mContext;
    private final Runnable mOnDismissed;
    private final SurfaceControlViewHost mHost;
    private final SurfaceControl mContainer;
    private boolean mDismissed;

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
        final DisplayLayout dl = mController.displayLayout(mWindow.getTaskInfo().displayId);
        final int dpi = dl.densityDpi();
        final View content = buildContent(dpi);
        final int width = LayangGeometry.dpToPx(WIDTH_DP, dpi);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dl.height(), View.MeasureSpec.AT_MOST));
        final int height = content.getMeasuredHeight();
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                TYPE_APPLICATION,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("LayangMenu #" + mWindow.getTaskId());
        lp.setTrustedOverlay();
        mHost.setView(content, lp);

        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dpi);
        int left = anchor.centerX() - width / 2;
        int top = anchor.top + LayangGeometry.dpToPx(4, dpi);
        left = Math.max(margin, Math.min(dl.width() - margin - width, left));
        top = Math.max(dl.stableInsets().top + margin,
                Math.min(dl.height() - margin - height, top));
        final SurfaceControl sc = mHost.getSurfacePackage().getSurfaceControl();
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        t.reparent(sc, mContainer).show(sc)
                .setLayer(mContainer, Integer.MAX_VALUE)
                .setPosition(mContainer, left, top)
                .show(mContainer)
                .apply();
        mController.getTransactionPool().release(t);
    }

    void dismiss() {
        if (mDismissed) {
            return;
        }
        mDismissed = true;
        mHost.release();
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        t.remove(mContainer).apply();
        mController.getTransactionPool().release(t);
        mOnDismissed.run();
    }

    // ---------------------------------------------------------------------------------------

    /** Colours of the menu for the current night mode, from the dynamic palette. */
    private final class Palette {
        final int surface;
        final int fg;
        final int tonal;
        final int activeBg;
        final int activeFg;
        final int danger;

        Palette() {
            final boolean night = (mContext.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            surface = color(night ? android.R.color.system_neutral1_900
                    : android.R.color.system_neutral1_10);
            fg = color(night ? android.R.color.system_neutral1_100
                    : android.R.color.system_neutral1_900);
            tonal = color(night ? android.R.color.system_neutral2_800
                    : android.R.color.system_neutral2_100);
            activeBg = color(night ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_100);
            activeFg = color(night ? android.R.color.system_accent1_900
                    : android.R.color.system_accent1_900);
            danger = night ? 0xFFF2B8B5 : 0xFFB3261E;
        }

        private int color(int res) {
            return mContext.getColor(res);
        }
    }

    /**
     * Compact rows (§6.2): the window actions as icons, then the app's options, then the
     * window shapes. Toggles show their state as a filled circle; a long press names the
     * action.
     */
    private View buildContent(int dpi) {
        final Palette c = new Palette();
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

        final LinearLayout actions = row();
        addIcon(actions, c, dpi, 48, R.drawable.ic_layang_fullscreen,
                R.string.layang_menu_fullscreen, false, c.fg,
                () -> mController.toFullscreen(taskId));
        addIcon(actions, c, dpi, 48, R.drawable.ic_layang_mini, R.string.layang_menu_mini,
                false, c.fg, () -> mController.setMode(taskId, LayangTaskState.MODE_MINI));
        addIcon(actions, c, dpi, 48, R.drawable.ic_layang_ghost,
                ghost ? R.string.layang_menu_unghost : R.string.layang_menu_ghost, ghost, c.fg,
                () -> mController.setFlags(taskId, ghost ? 0 : LayangTaskState.FLAG_GHOST,
                        LayangTaskState.FLAG_GHOST));
        addIcon(actions, c, dpi, 48, R.drawable.ic_layang_lock,
                locked ? R.string.layang_menu_unlock : R.string.layang_menu_lock, locked, c.fg,
                () -> mController.setFlags(taskId, locked ? 0 : LayangTaskState.FLAG_LOCKED,
                        LayangTaskState.FLAG_LOCKED));
        addIcon(actions, c, dpi, 48, R.drawable.ic_layang_minimize,
                R.string.layang_menu_minimize, false, c.fg,
                () -> mController.minimize(taskId));
        addIcon(actions, c, dpi, 48, R.drawable.ic_layang_close, R.string.layang_menu_close,
                false, c.danger, () -> mController.close(taskId));
        root.addView(actions, matchWidth());

        final LinearLayout more = row();
        more.setPadding(0, LayangGeometry.dpToPx(4, dpi), 0, 0);
        addIcon(more, c, dpi, 40, R.drawable.ic_layang_volume, R.string.layang_menu_volume,
                false, c.fg, this::openVolumePanel);
        final int fixed = mWindow.fixedOrientation();
        if (fixed == Configuration.ORIENTATION_UNDEFINED) {
            addIcon(more, c, dpi, 40, R.drawable.ic_layang_rotate, R.string.layang_menu_rotate,
                    false, c.fg, () -> mController.rotate(taskId));
        }
        if (s != null && !s.isGame()) {
            final boolean reflow = s.hasFlag(LayangTaskState.FLAG_REFLOW);
            addIcon(more, c, dpi, 40, R.drawable.ic_layang_reflow, R.string.layang_menu_reflow,
                    reflow, c.fg, () -> setReflow(!reflow));
        }
        final ImageView always = addIcon(more, c, dpi, 40, R.drawable.ic_layang_pin,
                R.string.layang_menu_always, false, c.fg, this::toggleAlwaysFloating);
        addIcon(more, c, dpi, 40, R.drawable.ic_layang_screenshot,
                R.string.layang_menu_screenshot, false, c.fg, this::screenshot);
        addIcon(more, c, dpi, 40, R.drawable.ic_layang_info, R.string.layang_menu_app_info,
                false, c.fg, this::openAppInfo);
        root.addView(more, matchWidth());
        if (s != null && (!s.isGame() || s.hasFlag(LayangTaskState.FLAG_ASPECT_UNLOCKED))) {
            addRatios(root, c, dpi, fixed);
        }
        loadAlwaysState(always, c, dpi);
        return root;
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
            int label, boolean on, int tint, Runnable action) {
        final ImageView v = new ImageView(mContext);
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(mContext.getString(label));
        setActive(v, c, dpi, on, tint);
        v.setOnClickListener(x -> {
            dismiss();
            action.run();
        });
        v.setOnLongClickListener(x -> {
            Toast.makeText(mContext, label, Toast.LENGTH_SHORT).show();
            return true;
        });
        // Spread the buttons across the card.
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LayangGeometry.dpToPx(sizeDp, dpi), 1f);
        row.addView(v, lp);
        return v;
    }

    /** Shows whether the app always opens floating; read off the main thread. */
    private void loadAlwaysState(ImageView v, Palette c, int dpi) {
        final ILayangManager service = mController.getService();
        final String pkg = packageName();
        final int userId = mWindow.getTaskInfo().userId;
        if (service == null || pkg == null) {
            return;
        }
        mController.getBgExecutor().execute(() -> {
            try {
                final boolean on = service.getRules(pkg, userId).getBoolean(Layang.RULE_ALWAYS);
                mController.getMainExecutor().execute(() -> {
                    if (!mDismissed && on) {
                        setActive(v, c, dpi, true, c.fg);
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

    /** Captures only the task's layers and saves them like a screenshot (§6.2). */
    private void screenshot() {
        final Rect b = new Rect(mWindow.getBounds());
        final ScreenCaptureInternal.LayerCaptureArgs args =
                new ScreenCaptureInternal.LayerCaptureArgs.Builder(
                mWindow.getLeash())
                .setSourceCrop(new Rect(0, 0, b.width(), b.height()))
                .build();
        final ScreenCaptureInternal.ScreenshotHardwareBuffer buffer =
                ScreenCaptureInternal.captureLayers(args);
        if (buffer == null) {
            Toast.makeText(mContext, R.string.layang_screenshot_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap hw = buffer.asBitmap();
        final String pkg = packageName();
        mController.getBgExecutor().execute(() -> {
            boolean ok = false;
            try {
                final Bitmap bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
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
                        ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "screenshot failed", e);
            }
            final boolean saved = ok;
            mController.getMainExecutor().execute(() -> Toast.makeText(mContext,
                    saved ? R.string.layang_screenshot_saved : R.string.layang_screenshot_failed,
                    Toast.LENGTH_SHORT).show());
        });
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

    private String packageName() {
        final android.app.ActivityManager.RunningTaskInfo ti = mWindow.getTaskInfo();
        if (ti.baseActivity != null) return ti.baseActivity.getPackageName();
        if (ti.realActivity != null) return ti.realActivity.getPackageName();
        return ti.baseIntent != null && ti.baseIntent.getComponent() != null
                ? ti.baseIntent.getComponent().getPackageName() : null;
    }
}
