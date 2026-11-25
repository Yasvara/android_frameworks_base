/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.layang;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.app.yasvara.ILayangListener;
import android.app.yasvara.ILayangManager;
import android.app.yasvara.ILayangShell;
import android.app.yasvara.Layang;
import android.app.yasvara.LayangGeometry;
import android.app.yasvara.LayangTaskState;
import android.app.yasvara.LayangWindowInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.util.DumpUtils;
import com.android.server.wm.LayangCore;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Layang floating windows: rules, remembered geometry, the game list, launch
 * placement, the window list for the status-bar chip and Pentas, and {@code cmd layang}.
 *
 * <p>WM core owns the windows ({@link LayangCore}); the Shell LayangController animates and
 * drives them. This service only decides where a window opens and forwards control requests
 * to Shell, falling back to WM core when no Shell is registered (§4.2).
 */
public final class YasvaraLayangService extends ILayangManager.Stub {
    static final String TAG = "YasvaraLayang";
    /** Alias so {@code cmd layang} works (§7.4). */
    public static final String SHELL_ALIAS = "layang";
    /** Pentas's game list, "pkg=mode;pkg=mode", in Settings.System per user. */
    private static final String PENTAS_GAME_LIST = "pentas_game_list";

    private final Context mContext;
    private final Handler mHandler;
    private final LayangStore mStore;
    private final Object mLock = new Object();
    private final RemoteCallbackList<ILayangListener> mListeners = new RemoteCallbackList<>();

    @Nullable
    private ILayangShell mShell;
    @Nullable
    private IBinder.DeathRecipient mShellDeath;

    /** Pentas's game list per user. */
    private final SparseArray<ArraySet<String>> mPentasGames = new SparseArray<>();

    /** Last window list sent to listeners. */
    private List<LayangWindowInfo> mWindows = Collections.emptyList();

    /** Auto-park on memory pressure while a session exists (§5.6). */
    private final LayangPressureWatcher mPressure;
    private long mLastAutoParkUptime;

    /** Multi audio focus value before the first Layang window, or null when not overridden. */
    @Nullable
    private Boolean mSavedMultiAudioFocus;

    public YasvaraLayangService(@NonNull Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread("layang", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        mHandler = thread.getThreadHandler();
        mStore = new LayangStore(mHandler);
        mPressure = new LayangPressureWatcher(() -> mHandler.post(this::onFullPressure));
        LayangCore.setChangeCallback(() -> mHandler.post(this::onWindowsChanged));

        final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId >= 0) {
                    mStore.onUserRemoved(userId);
                    synchronized (mLock) {
                        mPentasGames.remove(userId);
                    }
                }
            }
        }, UserHandle.ALL, filter, null, mHandler);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(PENTAS_GAME_LIST), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        loadPentasGames(ActivityManager.getCurrentUser());
                    }
                }, UserHandle.USER_ALL);
        mHandler.post(() -> loadPentasGames(UserHandle.USER_SYSTEM));

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Layang.KEY_ENABLED), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        if (!isEnabled()) {
                            Slog.i(TAG, "Layang switched off; closing every window");
                            closeAllInternal();
                        }
                    }
                }, UserHandle.USER_ALL);
    }

    // ---------------------------------------------------------------------------------------
    // ILayangManager
    // ---------------------------------------------------------------------------------------

    @Override
    public int openApp(ComponentName component, int userId, Bundle opts) {
        final int user = enforceAccess(userId);
        return clear(() -> openAppInternal(component, user, opts));
    }

    @Override
    public int openTask(int taskId, Bundle opts) {
        enforceAccess(UserHandle.getCallingUserId());
        return clear(() -> openTaskInternal(taskId, opts));
    }

    @Override
    public int openPendingIntent(PendingIntent pi, Bundle opts) {
        enforceAccess(UserHandle.getCallingUserId());
        return clear(() -> openPendingIntentInternal(pi, opts));
    }

    @Override
    public void setMode(int taskId, int mode) {
        enforceAccess(UserHandle.getCallingUserId());
        clear(() -> {
            setModeInternal(taskId, mode);
            return 0;
        });
    }

    @Override
    public void setFlags(int taskId, int flags, int mask) {
        enforceAccess(UserHandle.getCallingUserId());
        clear(() -> {
            setFlagsInternal(taskId, flags, mask);
            return 0;
        });
    }

    @Override
    public void toFullscreen(int taskId) {
        enforceAccess(UserHandle.getCallingUserId());
        clear(() -> {
            toFullscreenInternal(taskId);
            return 0;
        });
    }

    @Override
    public void close(int taskId) {
        enforceAccess(UserHandle.getCallingUserId());
        clear(() -> {
            closeInternal(taskId);
            return 0;
        });
    }

    @Override
    public void closeAll() {
        enforceAccess(UserHandle.getCallingUserId());
        clear(() -> {
            closeAllInternal();
            return 0;
        });
    }

    @Override
    public List<LayangWindowInfo> getWindows() {
        enforceAccess(UserHandle.getCallingUserId());
        return clear(this::snapshotWindows);
    }

    @Override
    public void registerListener(ILayangListener listener) {
        enforceAccess(UserHandle.getCallingUserId());
        if (listener == null) return;
        mListeners.register(listener);
        final List<LayangWindowInfo> now = clear(this::snapshotWindows);
        try {
            listener.onWindowsChanged(now);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void unregisterListener(ILayangListener listener) {
        enforceAccess(UserHandle.getCallingUserId());
        if (listener != null) mListeners.unregister(listener);
    }

    @Override
    public void setWindowVolume(int taskId, float volume, boolean muted) {
        enforceAccess(UserHandle.getCallingUserId());
        clear(() -> {
            // the window's volume is its app's Swara volume, remembered and
            // shown in the Volume Panel.
            final com.android.server.yasvara.swara.SwaraService swara =
                    com.android.server.yasvara.swara.SwaraService.getInstance();
            if (swara == null) return 0;
            for (LayangWindowInfo w : snapshotWindows()) {
                if (w.taskId == taskId) {
                    swara.setAppVolume(w.packageName, w.userId, volume, muted);
                    break;
                }
            }
            return 0;
        });
    }

    @Override
    public void setGamePackages(String[] packages, int userId) {
        userId = enforceAccess(userId);
        final int user = userId;
        synchronized (mLock) {
            mPentasGames.put(user, new ArraySet<>(packages != null ? packages : new String[0]));
        }
        clear(() -> {
            pushGamesToCore(user);
            return 0;
        });
    }

    @Override
    public boolean isGame(String packageName, int userId) {
        userId = enforceAccess(userId);
        final int user = userId;
        return clear(() -> isGameInternal(packageName, user));
    }

    @Override
    public Bundle getRules(String packageName, int userId) {
        userId = enforceAccess(userId);
        final LayangStore.App a = mStore.get(userId, packageName);
        final Bundle b = new Bundle();
        if (a == null) return b;
        b.putBoolean(Layang.RULE_ALWAYS, a.always);
        b.putBoolean(Layang.RULE_BLOCKED, a.blocked);
        b.putInt(Layang.RULE_READABLE, a.readable);
        b.putInt(Layang.RULE_ASPECT_LOCK, a.aspectLock);
        if (a.size != null) b.putString(Layang.RULE_SIZE, a.size);
        if (a.game >= 0) b.putBoolean(Layang.RULE_GAME, a.game == 1);
        return b;
    }

    @Override
    public void setRules(String packageName, int userId, Bundle rules) {
        userId = enforceAccess(userId);
        final int user = userId;
        if (packageName == null || rules == null) return;
        setRulesInternal(packageName, user, rules);
    }

    @Override
    public String[] getRulePackages(int userId) {
        userId = enforceAccess(userId);
        return mStore.packagesWithRules(userId).toArray(new String[0]);
    }

    @Override
    public void registerShell(ILayangShell shell) {
        enforceAccess(UserHandle.getCallingUserId());
        synchronized (mLock) {
            if (mShell != null && mShellDeath != null) {
                mShell.asBinder().unlinkToDeath(mShellDeath, 0);
            }
            mShell = shell;
            mShellDeath = null;
            if (shell != null) {
                final ILayangShell registered = shell;
                mShellDeath = () -> {
                    synchronized (mLock) {
                        if (mShell == registered) {
                            mShell = null;
                            mShellDeath = null;
                        }
                    }
                    Slog.w(TAG, "Shell died; windows keep their state until it is back");
                };
                try {
                    shell.asBinder().linkToDeath(mShellDeath, 0);
                } catch (RemoteException e) {
                    mShell = null;
                    mShellDeath = null;
                }
            }
        }
        Slog.i(TAG, "Shell " + (shell != null ? "registered" : "unregistered"));
    }

    @Override
    public Rect getLaunchBounds(String packageName, int userId, int mode, int orientation) {
        userId = enforceAccess(userId);
        final int user = userId;
        return clear(() -> {
            final boolean game = isGameInternal(packageName, user);
            final Placement p = computePlacement(packageName, user, game,
                    orientation == Configuration.ORIENTATION_LANDSCAPE,
                    orientation != Configuration.ORIENTATION_UNDEFINED, null);
            if (mode == LayangTaskState.MODE_MINI) {
                final int[] d = LayangCore.getDefaultDisplayInfo();
                final Rect mini = new Rect();
                LayangGeometry.mini(p.bounds, d[0], d[1], d[2], topInset(), mini);
                return mini;
            }
            return p.bounds;
        });
    }

    // ---------------------------------------------------------------------------------------
    // Opening
    // ---------------------------------------------------------------------------------------

    int openAppInternal(@NonNull ComponentName component, int userId, @Nullable Bundle opts) {
        if (!isEnabled()) return Layang.RESULT_DISABLED;
        final String pkg = component.getPackageName();
        final LayangStore.App rules = mStore.get(userId, pkg);
        if (rules != null && rules.blocked) return Layang.RESULT_BLOCKED;

        // An app that already has a task floats that task (Recents semantics).
        final ActivityManager.RunningTaskInfo existing = findTask(pkg, userId);
        if (existing != null) {
            return openTaskInternal(existing.taskId, opts);
        }

        final PackageManager pm = userPm(userId);
        Intent intent;
        if (component.getClassName() == null || component.getClassName().isEmpty()
                || component.getClassName().equals(pkg)) {
            intent = pm.getLaunchIntentForPackage(pkg);
            if (intent == null) return Layang.RESULT_NOT_FOUND;
        } else {
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component);
        }
        final ComponentName target = intent.getComponent();
        if (target == null) return Layang.RESULT_NOT_FOUND;
        ActivityInfo ai;
        try {
            ai = pm.getActivityInfo(target, PackageManager.ComponentInfoFlags.of(0));
        } catch (PackageManager.NameNotFoundException e) {
            return Layang.RESULT_NOT_FOUND;
        }

        final boolean game = isGameInternal(pkg, userId);
        final Placement p = computePlacement(pkg, userId, game, wantsLandscape(ai, game),
                isFixedOrientation(ai), opts);
        LayangTaskState state = p.state;
        Rect bounds = p.bounds;
        if (opts != null && opts.getBoolean(Layang.OPT_MINI)) {
            final int[] d = LayangCore.getDefaultDisplayInfo();
            final Rect mini = new Rect();
            LayangGeometry.mini(bounds, d[0], d[1], d[2], topInset(), mini);
            bounds = mini;
            state = state.withMode(LayangTaskState.MODE_MINI);
        }

        final ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        o.setLaunchBounds(bounds);
        o.setLayangLaunch(state);
        o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            mContext.startActivityAsUser(intent, o.toBundle(), UserHandle.of(userId));
        } catch (RuntimeException e) {
            Slog.w(TAG, "open " + component + " failed", e);
            return Layang.RESULT_FAILED;
        }
        Slog.i(TAG, "open " + target.flattenToShortString() + " u" + userId + " " + state
                + " at " + bounds.toShortString());
        return Layang.RESULT_OK;
    }

    int openTaskInternal(int taskId, @Nullable Bundle opts) {
        if (!isEnabled()) return Layang.RESULT_DISABLED;
        final ActivityManager.RunningTaskInfo info = getTaskInfo(taskId);
        if (info == null || info.getActivityType() != ACTIVITY_TYPE_STANDARD) {
            return Layang.RESULT_NOT_FOUND;
        }
        final String pkg = packageOf(info);
        if (pkg == null) return Layang.RESULT_NOT_FOUND;
        final LayangStore.App rules = mStore.get(info.userId, pkg);
        if (rules != null && rules.blocked) return Layang.RESULT_BLOCKED;

        final LayangTaskState current = LayangCore.getTaskState(taskId);
        if (current != null) {
            // Already floating: bring it back and forward.
            if (current.getMode() != LayangTaskState.MODE_FLOATING) {
                setModeInternal(taskId, LayangTaskState.MODE_FLOATING);
            } else {
                final WindowContainerToken token = LayangCore.getRootTaskToken(taskId);
                if (token != null) {
                    final WindowContainerTransaction wct = new WindowContainerTransaction();
                    wct.reorder(token, true /* onTop */);
                    LayangCore.applyTransaction(wct, taskId);
                }
            }
            return Layang.RESULT_OK;
        }

        final boolean game = isGameInternal(pkg, info.userId);
        final ActivityInfo top = info.topActivityInfo;
        final Placement p = computePlacement(pkg, info.userId, game,
                top != null ? wantsLandscape(top, game) : game && isDisplayLandscape(),
                top != null && isFixedOrientation(top), opts);
        final ILayangShell shell = shell();
        if (shell != null) {
            try {
                shell.enterLayang(taskId, p.state, p.bounds, opts != null ? opts : new Bundle());
                return Layang.RESULT_OK;
            } catch (RemoteException e) {
                Slog.w(TAG, "Shell enterLayang failed; applying in core", e);
            }
        }
        final WindowContainerToken token = LayangCore.getRootTaskToken(taskId);
        if (token == null) return Layang.RESULT_NOT_FOUND;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(token, WINDOWING_MODE_FREEFORM);
        wct.setBounds(token, p.bounds);
        wct.setLayangState(token, p.state);
        wct.reorder(token, true /* onTop */);
        LayangCore.applyTransaction(wct, taskId);
        return Layang.RESULT_OK;
    }

    int openPendingIntentInternal(@NonNull PendingIntent pi, @Nullable Bundle opts) {
        if (!isEnabled()) return Layang.RESULT_DISABLED;
        final String pkg = pi.getCreatorPackage();
        final int userId = pi.getCreatorUserHandle() != null
                ? pi.getCreatorUserHandle().getIdentifier() : UserHandle.USER_SYSTEM;
        if (pkg == null) return Layang.RESULT_NOT_FOUND;
        final LayangStore.App rules = mStore.get(userId, pkg);
        if (rules != null && rules.blocked) return Layang.RESULT_BLOCKED;
        final boolean game = isGameInternal(pkg, userId);
        final Placement p = computePlacement(pkg, userId, game,
                game && isDisplayLandscape(), false /* fixed */, opts);
        final ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        o.setLaunchBounds(p.bounds);
        o.setLayangLaunch(p.state);
        o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        o.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        try {
            pi.send(mContext, 0, new Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), null, null, null, o.toBundle());
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Slog.w(TAG, "openPendingIntent failed", e);
            return Layang.RESULT_FAILED;
        }
        return Layang.RESULT_OK;
    }

    /** Launch rect and state of an app opened in Layang. */
    static final class Placement {
        final Rect bounds = new Rect();
        LayangTaskState state;
    }

    /**
     * Where and how an app opens (§4.1, §6.4). Every app is scaled from its virtual screen V at
     * the display density, so floating never changes its configuration; only a per-app reflow
     * rule lays it out for the real window. V is the display's size unless the window was
     * reshaped before.
     *
     * @param landscape orientation the app wants
     * @param fixed whether that orientation is fixed; otherwise a remembered shape wins
     */
    @NonNull
    Placement computePlacement(@NonNull String pkg, int userId, boolean game, boolean landscape,
            boolean fixed, @Nullable Bundle opts) {
        final int[] d = LayangCore.getDefaultDisplayInfo();
        final int dw = d[0];
        final int dh = d[1];
        final int dpi = d[2];
        final int dLong = Math.max(dw, dh);
        final int dShort = Math.min(dw, dh);
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dpi);
        final int topInset = topInset();
        final LayangStore.App app = mStore.get(userId, pkg);
        final boolean remember = secureInt(Layang.KEY_REMEMBER, 1, userId) != 0;
        // Remembered per display orientation (see rememberGeometry).
        final int orient = dw > dh ? LayangStore.ORIENT_LANDSCAPE : LayangStore.ORIENT_PORTRAIT;
        final LayangStore.Geometry g = remember && app != null ? app.geometry[orient] : null;
        final boolean reflow = app != null && app.readable == 1;

        final Placement p = new Placement();
        int flags = game ? LayangTaskState.FLAG_GAME : 0;
        if (game) {
            final boolean aspectLock = app != null && app.aspectLock >= 0
                    ? app.aspectLock == 1
                    : secureInt(Layang.KEY_GAME_ASPECT_LOCK, 1, userId) != 0;
            if (!aspectLock) flags |= LayangTaskState.FLAG_ASPECT_UNLOCKED;
        }
        final String size = app != null && app.size != null ? app.size
                : Layang.sizeForSetting(secureInt(Layang.KEY_DEFAULT_SIZE, 1, userId));
        int density = 0;
        int vLong = 0;
        int vShort = 0;
        final int[] wh = new int[2];
        if (reflow) {
            flags |= LayangTaskState.FLAG_REFLOW;
            // Sized against the display turned to the app's orientation, not the rotation a
            // fullscreen game left it in.
            final int ow = landscape ? dLong : dShort;
            final int oh = landscape ? dShort : dLong;
            LayangGeometry.defaultAppSize(ow, oh, wh);
            if (g != null && g.scale <= 0 && g.width > 0 && g.height > 0) {
                wh[0] = g.width;
                wh[1] = g.height;
            }
            if (opts != null && opts.containsKey(Layang.OPT_SCALE)) {
                final float f = Math.max(0.3f, Math.min(1f, opts.getFloat(Layang.OPT_SCALE)));
                wh[1] = Math.round(wh[1] * f * ow / Math.max(1, wh[0]));
                wh[0] = Math.round(ow * f);
            }
            wh[0] = Math.min(wh[0], dw - 2 * margin);
            wh[1] = Math.min(wh[1], dh - 2 * margin);
        } else {
            float scale = game ? Layang.scaleForSize(size) : appScaleForSize(size);
            int vw = landscape ? dLong : dShort;
            int vh = landscape ? dShort : dLong;
            // Only geometry written by the scaled model (scale > 0) is reused: s, and the
            // virtual size the window had (its size / s), so a reshaped app keeps its layout.
            if (g != null && g.scale > 0) {
                scale = g.scale;
                if (g.width > 0 && g.height > 0) {
                    vw = Math.round(g.width / g.scale);
                    vh = Math.round(g.height / g.scale);
                    if (fixed && vw != vh && (vw > vh) != landscape) {
                        final int t = vw;
                        vw = vh;
                        vh = t;
                    }
                }
            }
            if (opts != null && opts.containsKey(Layang.OPT_SCALE)) {
                scale = opts.getFloat(Layang.OPT_SCALE);
            }
            final int vl = Math.max(vw, vh);
            final int vs = Math.min(vw, vh);
            if (Math.abs(vl - dLong) > 2 || Math.abs(vs - dShort) > 2) {
                vLong = vl;
                vShort = vs;
            }
            LayangGeometry.gameSize(vl, vs, vw > vh, scale, dw, dh, margin, wh);
        }
        float cx = g != null ? g.cx : -1f;
        float cy = g != null ? g.cy : -1f;
        if (opts != null && opts.containsKey(Layang.OPT_X) && opts.containsKey(Layang.OPT_Y)) {
            cx = (opts.getInt(Layang.OPT_X) + wh[0] / 2f) / dw;
            cy = (opts.getInt(Layang.OPT_Y) + wh[1] / 2f) / dh;
        }
        LayangGeometry.place(wh[0], wh[1], cx, cy, dw, dh, margin, topInset, p.bounds);
        // Below the status bar and the pill too: shrinking keeps the shape, so V still fits.
        LayangGeometry.fit(p.bounds, dw, dh, margin, topInset);
        if (reflow) {
            density = LayangGeometry.readableDensity(p.bounds.width(), p.bounds.height(), dpi);
            if (density == dpi) density = 0;
        }
        p.state = new LayangTaskState(LayangTaskState.MODE_FLOATING, flags, vLong, vShort,
                density);
        return p;
    }

    private static float appScaleForSize(@Nullable String size) {
        if ("S".equals(size)) return Layang.APP_SCALE_S;
        if ("L".equals(size)) return Layang.APP_SCALE_L;
        return Layang.APP_SCALE_M;
    }

    private static boolean wantsLandscape(@NonNull ActivityInfo ai, boolean game) {
        if (ActivityInfo.isFixedOrientationLandscape(ai.screenOrientation)) return true;
        if (ActivityInfo.isFixedOrientationPortrait(ai.screenOrientation)) return false;
        // A free app opens upright like on the home screen, even over a landscape game.
        return game && isDisplayLandscape();
    }

    private static boolean isFixedOrientation(@NonNull ActivityInfo ai) {
        return ActivityInfo.isFixedOrientationLandscape(ai.screenOrientation)
                || ActivityInfo.isFixedOrientationPortrait(ai.screenOrientation);
    }

    private static boolean isDisplayLandscape() {
        final int[] d = LayangCore.getDefaultDisplayInfo();
        return d[0] > d[1];
    }

    /** Status bar height plus room for the pill (24 dp, as in Shell); windows stay below it. */
    private int topInset() {
        final int id = mContext.getResources().getIdentifier("status_bar_height", "dimen",
                "android");
        final int bar = id > 0 ? mContext.getResources().getDimensionPixelSize(id) : 0;
        return bar + LayangGeometry.dpToPx(24,
                mContext.getResources().getConfiguration().densityDpi);
    }

    // ---------------------------------------------------------------------------------------
    // Control
    // ---------------------------------------------------------------------------------------

    void setModeInternal(int taskId, int mode) {
        if (!LayangTaskState.isValidMode(mode)) return;
        final LayangTaskState state = LayangCore.getTaskState(taskId);
        if (state == null || state.getMode() == mode) return;
        final ILayangShell shell = shell();
        if (shell != null) {
            try {
                shell.setMode(taskId, mode);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Shell setMode failed; applying in core", e);
            }
        }
        final WindowContainerToken token = LayangCore.getRootTaskToken(taskId);
        final Rect bounds = LayangCore.getTaskBounds(taskId);
        if (token == null || bounds == null) return;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final int[] d = LayangCore.getDefaultDisplayInfo();
        if (mode == LayangTaskState.MODE_MINI) {
            final Rect mini = new Rect();
            LayangGeometry.mini(bounds, d[0], d[1], d[2], topInset(), mini);
            wct.setBounds(token, mini);
        } else if (mode == LayangTaskState.MODE_FLOATING) {
            final ActivityManager.RunningTaskInfo info = getTaskInfo(taskId);
            final String pkg = info != null ? packageOf(info) : null;
            if (pkg != null) {
                wct.setBounds(token, computePlacement(pkg, info.userId, state.isGame(),
                        bounds.width() > bounds.height(), true /* fixed */, null).bounds);
            }
            wct.reorder(token, true /* onTop */);
        }
        wct.setLayangState(token, state.withMode(mode));
        LayangCore.applyTransaction(wct, taskId);
    }

    void setFlagsInternal(int taskId, int flags, int mask) {
        final LayangTaskState state = LayangCore.getTaskState(taskId);
        if (state == null) return;
        final LayangTaskState next = state.withFlags(flags, mask);
        if (next.equals(state)) return;
        final ILayangShell shell = shell();
        if (shell != null) {
            try {
                shell.setFlags(taskId, flags, mask);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Shell setFlags failed; applying in core", e);
            }
        }
        final WindowContainerToken token = LayangCore.getRootTaskToken(taskId);
        if (token == null) return;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setLayangState(token, next);
        LayangCore.applyTransaction(wct, taskId);
    }

    void toFullscreenInternal(int taskId) {
        if (LayangCore.getTaskState(taskId) == null) return;
        final ILayangShell shell = shell();
        if (shell != null) {
            try {
                shell.toFullscreen(taskId);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Shell toFullscreen failed; applying in core", e);
            }
        }
        final WindowContainerToken token = LayangCore.getRootTaskToken(taskId);
        if (token == null) return;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(token, WINDOWING_MODE_FULLSCREEN);
        wct.setBounds(token, new Rect());
        wct.setLayangState(token, null);
        wct.reorder(token, true /* onTop */);
        LayangCore.applyTransaction(wct, taskId);
    }

    void closeInternal(int taskId) {
        if (LayangCore.getTaskState(taskId) == null) return;
        final ILayangShell shell = shell();
        if (shell != null) {
            try {
                shell.close(taskId);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Shell close failed; removing in core", e);
            }
        }
        removeTask(taskId);
    }

    void closeAllInternal() {
        final ILayangShell shell = shell();
        if (shell != null) {
            try {
                shell.closeAll();
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Shell closeAll failed; removing in core", e);
            }
        }
        for (LayangWindowInfo w : LayangCore.getWindows()) {
            removeTask(w.taskId);
        }
    }

    private static void removeTask(int taskId) {
        try {
            ActivityTaskManager.getService().removeTask(taskId);
        } catch (RemoteException e) {
            Slog.w(TAG, "removeTask " + taskId + " failed", e);
        }
    }

    void setRulesInternal(@NonNull String pkg, int userId, @NonNull Bundle rules) {
        mStore.edit(userId, pkg, a -> {
            if (rules.containsKey(Layang.RULE_ALWAYS)) {
                a.always = rules.getBoolean(Layang.RULE_ALWAYS);
            }
            if (rules.containsKey(Layang.RULE_BLOCKED)) {
                a.blocked = rules.getBoolean(Layang.RULE_BLOCKED);
            }
            if (rules.containsKey(Layang.RULE_READABLE)) {
                a.readable = Math.max(-1, Math.min(1, rules.getInt(Layang.RULE_READABLE)));
            }
            if (rules.containsKey(Layang.RULE_ASPECT_LOCK)) {
                a.aspectLock = Math.max(-1, Math.min(1, rules.getInt(Layang.RULE_ASPECT_LOCK)));
            }
            if (rules.containsKey(Layang.RULE_SIZE)) {
                final String s = rules.getString(Layang.RULE_SIZE);
                a.size = "S".equals(s) || "M".equals(s) || "L".equals(s) ? s : null;
            }
            if (rules.containsKey(Layang.RULE_GAME)) {
                final Object v = rules.get(Layang.RULE_GAME);
                a.game = v instanceof Boolean ? (((Boolean) v) ? 1 : 0) : -1;
            }
            if (rules.getBoolean(Layang.RULE_RESET_GEOMETRY)) {
                a.geometry[0] = null;
                a.geometry[1] = null;
            }
        });
        if (rules.containsKey(Layang.RULE_GAME)) {
            pushGamesToCore(userId);
        }
        if (rules.getBoolean(Layang.RULE_BLOCKED)) {
            for (LayangWindowInfo w : LayangCore.getWindows()) {
                if (w.userId == userId && pkg.equals(w.packageName)) {
                    toFullscreenInternal(w.taskId);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Games
    // ---------------------------------------------------------------------------------------

    boolean isGameInternal(@NonNull String pkg, int userId) {
        final LayangStore.App app = mStore.get(userId, pkg);
        if (app != null && app.game >= 0) return app.game == 1;
        synchronized (mLock) {
            final ArraySet<String> gs = mPentasGames.get(userId);
            if (gs != null && gs.contains(pkg)) return true;
        }
        try {
            final ApplicationInfo ai = userPm(userId).getApplicationInfo(pkg,
                    PackageManager.ApplicationInfoFlags.of(0));
            return ai.category == ApplicationInfo.CATEGORY_GAME
                    || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Reads Pentas's game list of userId (§5.1 "what counts as a game"). */
    private void loadPentasGames(int userId) {
        final String raw = Settings.System.getStringForUser(mContext.getContentResolver(),
                PENTAS_GAME_LIST, userId);
        final ArraySet<String> games = new ArraySet<>();
        if (raw != null) {
            for (String entry : raw.split(";")) {
                final int eq = entry.indexOf('=');
                final String pkg = (eq >= 0 ? entry.substring(0, eq) : entry).trim();
                if (!pkg.isEmpty()) {
                    games.add(pkg);
                }
            }
        }
        synchronized (mLock) {
            mPentasGames.put(userId, games);
        }
        pushGamesToCore(userId);
    }

    private void pushGamesToCore(int userId) {
        final ArraySet<String> games = new ArraySet<>();
        synchronized (mLock) {
            final ArraySet<String> gs = mPentasGames.get(userId);
            if (gs != null) games.addAll(gs);
        }
        final ArrayMap<String, Integer> ruled = mStore.gameRules(userId);
        for (int i = 0; i < ruled.size(); i++) {
            if (ruled.valueAt(i) == 1) {
                games.add(ruled.keyAt(i));
            } else {
                games.remove(ruled.keyAt(i));
            }
        }
        LayangCore.setGamePackages(userId, games);
    }

    // ---------------------------------------------------------------------------------------
    // Window list, geometry memory, session policies
    // ---------------------------------------------------------------------------------------

    @NonNull
    private List<LayangWindowInfo> snapshotWindows() {
        final List<LayangWindowInfo> raw = LayangCore.getWindows();
        final ArrayList<LayangWindowInfo> out = new ArrayList<>(raw.size());
        for (LayangWindowInfo w : raw) {
            String label = w.label;
            if (label == null || label.isEmpty()) {
                try {
                    final PackageManager pm = userPm(w.userId);
                    label = pm.getApplicationLabel(pm.getApplicationInfo(w.packageName,
                            PackageManager.ApplicationInfoFlags.of(0))).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    label = w.packageName;
                }
            }
            out.add(new LayangWindowInfo(w.taskId, w.userId, w.packageName, label, w.state,
                    w.bounds, w.focused, w.visible, w.lastInteraction));
        }
        return out;
    }

    /** Runs on mHandler after WM core reports a change. */
    private void onWindowsChanged() {
        final long ident = Binder.clearCallingIdentity();
        try {
            final List<LayangWindowInfo> windows = snapshotWindows();
            rememberGeometry(windows);
            updateSessionPolicies(windows);
            updateMiniFrameRates(windows);
            synchronized (mLock) {
                mWindows = windows;
            }
            final int n = mListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mListeners.getBroadcastItem(i).onWindowsChanged(windows);
                } catch (RemoteException ignored) {
                }
            }
            mListeners.finishBroadcast();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void rememberGeometry(@NonNull List<LayangWindowInfo> windows) {
        final int[] d = LayangCore.getDefaultDisplayInfo();
        final float[] c = new float[2];
        for (LayangWindowInfo w : windows) {
            // Mini and parked geometry is not remembered (§4.3).
            if (w.state.getMode() != LayangTaskState.MODE_FLOATING || w.bounds.isEmpty()) {
                continue;
            }
            if (secureInt(Layang.KEY_REMEMBER, 1, w.userId) == 0) continue;
            // Mid-rotation the bounds and the display size can come from different
            // orientations; such a snapshot is not a place to remember.
            if (!new Rect(0, 0, d[0], d[1]).contains(w.bounds)) continue;
            // Geometry is kept per display orientation, so the centre restores on the same
            // display shape it was normalised against.
            final boolean landscape = d[0] > d[1];
            final int orient = landscape ? LayangStore.ORIENT_LANDSCAPE
                    : LayangStore.ORIENT_PORTRAIT;
            LayangGeometry.centre(w.bounds, d[0], d[1], c);
            final LayangStore.App before = mStore.get(w.userId, w.packageName);
            final LayangStore.Geometry old = before != null ? before.geometry[orient] : null;
            // Scaled windows keep s and their shape; a reflow window only its size (scale 0).
            final boolean reflow = w.state.hasFlag(LayangTaskState.FLAG_REFLOW);
            final int vLong = w.state.getVirtualLong() > 0 ? w.state.getVirtualLong()
                    : Math.max(d[0], d[1]);
            final float scale = reflow ? 0f : LayangGeometry.scaleOf(
                    Math.max(w.bounds.width(), w.bounds.height()), vLong);
            if (old != null && Math.abs(old.cx - c[0]) < 0.001f && Math.abs(old.cy - c[1]) < 0.001f
                    && Math.abs(old.scale - scale) < 0.001f && old.width == w.bounds.width()
                    && old.height == w.bounds.height()) {
                continue;
            }
            mStore.edit(w.userId, w.packageName, a -> {
                final LayangStore.Geometry g = new LayangStore.Geometry();
                g.cx = c[0];
                g.cy = c[1];
                g.scale = scale;
                g.width = w.bounds.width();
                g.height = w.bounds.height();
                a.geometry[orient] = g;
            });
        }
    }

    /** uids currently capped because their game sits in a mini window. */
    private final ArraySet<Integer> mCappedUids = new ArraySet<>();

    /**
     * A game in a mini window runs at Settings.Secure yasvara_layang_mini_game_fps (30 by
     * default, 0 = uncapped) through the per-uid frame-rate override (§5.2, D13).
     */
    private void updateMiniFrameRates(@NonNull List<LayangWindowInfo> windows) {
        final int fps = secureInt(Layang.KEY_MINI_GAME_FPS, 30, UserHandle.USER_CURRENT);
        final ArraySet<Integer> want = new ArraySet<>();
        if (fps > 0) {
            for (LayangWindowInfo w : windows) {
                if (w.state.isGame() && w.state.getMode() == LayangTaskState.MODE_MINI) {
                    try {
                        want.add(mContext.getPackageManager().getPackageUidAsUser(
                                w.packageName, w.userId));
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
            }
        }
        for (int i = mCappedUids.size() - 1; i >= 0; i--) {
            final int uid = mCappedUids.valueAt(i);
            if (!want.contains(uid)) {
                com.android.server.app.GameManagerService.setLayangFrameRateCap(uid, 0f);
                mCappedUids.removeAt(i);
            }
        }
        for (int i = 0; i < want.size(); i++) {
            final int uid = want.valueAt(i);
            if (mCappedUids.add(uid)) {
                com.android.server.app.GameManagerService.setLayangFrameRateCap(uid, fps);
            }
        }
    }

    /** Multi audio focus (§5.3) and the pressure watcher (§5.6) while a session exists. */
    private void updateSessionPolicies(@NonNull List<LayangWindowInfo> windows) {
        final boolean session = !windows.isEmpty();
        if (session) {
            mPressure.start();
        } else {
            mPressure.stop();
        }
        final AudioManager am = mContext.getSystemService(AudioManager.class);
        if (am == null) return;
        mHandler.removeCallbacks(mRestoreAudioFocus);
        if (session && mSavedMultiAudioFocus == null
                && secureInt(Layang.KEY_MULTI_AUDIO_FOCUS, 1, UserHandle.USER_CURRENT) != 0) {
            final boolean was = am.isMultiAudioFocusEnabled();
            mSavedMultiAudioFocus = was;
            if (!was) {
                am.setMultiAudioFocusEnabled(true);
                Slog.i(TAG, "multi audio focus on for the Layang session");
            }
        } else if (!session && mSavedMultiAudioFocus != null) {
            mRestoreAudioFocus.run();
        }
    }

    /**
     * PSI "full": parks the least recently used floating or mini window that is not focused,
     * at most one every 10 s, and tells the listeners why (§5.6).
     */
    private void onFullPressure() {
        if (secureInt(Layang.KEY_AUTO_PARK, 1, UserHandle.USER_CURRENT) == 0) return;
        final long now = SystemClock.uptimeMillis();
        if (now - mLastAutoParkUptime < 10_000) return;
        LayangWindowInfo victim = null;
        for (LayangWindowInfo w : LayangCore.getWindows()) {
            final int mode = w.state.getMode();
            if (w.focused || mode == LayangTaskState.MODE_PARKED) continue;
            if (victim == null || w.lastInteraction < victim.lastInteraction) victim = w;
        }
        if (victim == null) return;
        mLastAutoParkUptime = now;
        Slog.i(TAG, "memory pressure: parking task " + victim.taskId + " " + victim.packageName);
        setModeInternal(victim.taskId, LayangTaskState.MODE_PARKED);
        final int taskId = victim.taskId;
        final int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onAutoParked(taskId);
            } catch (RemoteException ignored) {
            }
        }
        mListeners.finishBroadcast();
    }

    /**
     * Turning multi audio focus off makes AudioService send "becoming noisy", which pauses
     * media players; so it is restored only once nothing plays, re-checked once a minute.
     */
    private final Runnable mRestoreAudioFocus = new Runnable() {
        @Override
        public void run() {
            final AudioManager am = mContext.getSystemService(AudioManager.class);
            if (am == null || mSavedMultiAudioFocus == null) return;
            if (!LayangCore.getWindows().isEmpty()) return;
            if (!mSavedMultiAudioFocus) {
                if (am.isMusicActive()) {
                    mHandler.postDelayed(this, 60_000);
                    return;
                }
                am.setMultiAudioFocusEnabled(false);
                Slog.i(TAG, "multi audio focus restored");
            }
            mSavedMultiAudioFocus = null;
        }
    };

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    boolean isEnabled() {
        if (mContext.getPackageManager().isSafeMode()) return false;
        return secureInt(Layang.KEY_ENABLED, 1, UserHandle.USER_CURRENT) != 0;
    }

    @Nullable
    private ILayangShell shell() {
        synchronized (mLock) {
            return mShell;
        }
    }

    private int secureInt(String key, int def, int userId) {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(), key, def,
                userId < 0 ? UserHandle.USER_CURRENT : userId);
    }

    private String secureString(String key, String def, int userId) {
        final String v = Settings.Secure.getStringForUser(mContext.getContentResolver(), key,
                userId < 0 ? UserHandle.USER_CURRENT : userId);
        return v != null ? v : def;
    }

    private PackageManager userPm(int userId) {
        return mContext.createContextAsUser(UserHandle.of(userId), 0).getPackageManager();
    }

    @Nullable
    private static String packageOf(@NonNull ActivityManager.RunningTaskInfo info) {
        if (info.baseActivity != null) return info.baseActivity.getPackageName();
        if (info.realActivity != null) return info.realActivity.getPackageName();
        if (info.baseIntent != null && info.baseIntent.getComponent() != null) {
            return info.baseIntent.getComponent().getPackageName();
        }
        return null;
    }

    @Nullable
    private static ActivityManager.RunningTaskInfo getTaskInfo(int taskId) {
        for (ActivityManager.RunningTaskInfo t : runningTasks()) {
            if (t.taskId == taskId) return t;
        }
        return null;
    }

    @Nullable
    private static ActivityManager.RunningTaskInfo findTask(@NonNull String pkg, int userId) {
        for (ActivityManager.RunningTaskInfo t : runningTasks()) {
            if (t.userId == userId && t.getActivityType() == ACTIVITY_TYPE_STANDARD
                    && t.displayId == Display.DEFAULT_DISPLAY && pkg.equals(packageOf(t))) {
                return t;
            }
        }
        return null;
    }

    @NonNull
    private static List<ActivityManager.RunningTaskInfo> runningTasks() {
        try {
            return ActivityTaskManager.getService().getTasks(Integer.MAX_VALUE,
                    false /* filterOnlyVisibleRecents */, false /* keepIntentExtra */,
                    Display.INVALID_DISPLAY);
        } catch (RemoteException e) {
            return Collections.emptyList();
        }
    }

    private int enforceAccess(int userId) {
        mContext.enforceCallingOrSelfPermission(Layang.PERMISSION_MANAGE, TAG);
        final int callingUser = UserHandle.getCallingUserId();
        if (userId == UserHandle.USER_CURRENT) return ActivityManager.getCurrentUser();
        if (userId == callingUser) return callingUser;
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, TAG);
        return userId;
    }

    private interface Call<T> {
        T run();
    }

    private static <T> T clear(Call<T> call) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return call.run();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shell command and dump
    // ---------------------------------------------------------------------------------------

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) {
            mContext.enforceCallingOrSelfPermission(Layang.PERMISSION_MANAGE, TAG);
        }
        new LayangShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        dumpInternal(pw);
    }

    void dumpInternal(@NonNull PrintWriter pw) {
        pw.println("Layang enabled=" + isEnabled() + " shell="
                + (shell() != null) + " savedMultiAudioFocus=" + mSavedMultiAudioFocus);
        final List<LayangWindowInfo> windows = clear(this::snapshotWindows);
        pw.println("  windows (" + windows.size() + ", top first):");
        for (LayangWindowInfo w : windows) {
            pw.println("    " + w + " \"" + w.label + "\"");
        }
        synchronized (mLock) {
            for (int i = 0; i < mPentasGames.size(); i++) {
                pw.println("  pentas games u" + mPentasGames.keyAt(i) + ": "
                        + mPentasGames.valueAt(i));
            }
        }
        mStore.dump(pw, ActivityManager.getCurrentUser());
        pw.println("  listeners=" + mListeners.getRegisteredCallbackCount()
                + " now=" + SystemClock.uptimeMillis());
    }

    // Package access for LayangShellCommand.
    Context contextForShell() {
        return mContext;
    }

    LayangStore store() {
        return mStore;
    }

    List<LayangWindowInfo> windowsForShell() {
        return clear(this::snapshotWindows);
    }

    static String[] split(String s) {
        return s == null ? new String[0] : Arrays.stream(s.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toArray(String[]::new);
    }
}
