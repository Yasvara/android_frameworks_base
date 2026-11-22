/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.pentas;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.IServiceManager;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.Service;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;

import java.nio.ByteBuffer;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BinderInternal;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import yasvara.pantau.IPantau;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pentas session control.
 *
 * Watches which app is in front and, when it is one Pentas serves, binds Pentas's
 * SessionService with one intent per session ({@code pentas://session/<pkg>?mode=<n>}). The bind
 * gives the Pentas UI process foreground priority with no notification, and unbinding lets it be
 * cached, so the sidebar costs nothing while no game or video app is open.
 *
 * Event-driven: one TaskStackListener, bursts debounced, then a single focused-task query.
 * Classification is cached per package and only recomputed when a package or one of Pentas's
 * three list settings changes. Everything runs on the shared background thread.
 */
public final class PentasService extends Binder {

    private static final String TAG = "Pentas";
    public static final String SERVICE_NAME = "yasvara_pentas";
    /** Short alias registered in SystemServer so {@code cmd pantau} routes here. */
    public static final String SHELL_ALIAS = "pantau";
    private static final String PANTAU_DAEMON_SERVICE = "yasvara.pantau";
    private static final String KEY_HUD_PROFILE = "pentas_hud";

    private static final String PACKAGE = "org.yasvara.pentas";
    private static final ComponentName SESSION_SERVICE =
            new ComponentName(PACKAGE, PACKAGE + ".session.SessionService");

    // Mirrors org.yasvara.pentas.session.SidebarMode.
    static final int MODE_NONE = 0;
    static final int MODE_GAME = 1;
    static final int MODE_VIDEO = 2;

    // Pentas's lists, Settings.System per user.
    private static final String KEY_GAME_LIST = "pentas_game_list";
    private static final String KEY_AUTO_DETECT = "pentas_auto_game_detect";
    private static final String KEY_VIDEO_LIST = "sidebar_video_list";
    /**
     * Settings.Secure, written here only: the game in a live session, or empty. SystemUI's
     * EdgeBackGestureHandler lets that game's side swipe go back while its bars are hidden.
     */
    private static final String KEY_GAME_FOCUS = "pentas_game_focus";
    /** Settings.System per user, written by Pentas: {"<pkg>": "<scale>"}. */
    private static final String KEY_RENDER_SCALE = "pentas_game_resolution";
    private static final float RENDER_SCALE_MIN = 0.25f;
    private static final float RENDER_SCALE_MAX = 2f;

    /** Debounce for a burst of task-stack events, and the most a burst may delay a switch. */
    private static final long SETTLE_MS = 120;
    private static final long SETTLE_CAP_MS = 400;

    private static final String[] VIDEO_HINTS = {
        "youtube", "netflix", "vlc", "mxplayer", "primevideo", "disney", "bilibili", "tiktok",
        "twitch", "iqiyi", "videoplayer", "hotstar", "hulu", "hbomax", "instagram", "twitter",
        "musically", "ugc.trill",
    };

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final DeviceDisplayTouchBridge mDisplayTouchBridge;
    private final ThermalPolicyBridge mThermalBridge;
    private static final long PANTAU_IDLE_RELEASE_MS = 15_000L;

    /** Package → mode for the current user. Handler thread only. */
    private final ArrayMap<String, Integer> mModes = new ArrayMap<>();
    /** UID → friendly app label cache (avoids repeated PackageManager APK resource loads). */
    private final ArrayMap<Integer, String> mUidLabelCache = new ArrayMap<>();
    /** UIDs whose 28x28 RGBA icon + label were already pushed to the current pantaud instance. */
    private final ArraySet<Integer> mPushedUids = new ArraySet<>();
    /** UIDs whose label-only entry was pushed to the current pantaud instance. */
    private final ArraySet<Integer> mPushedLabels = new ArraySet<>();
    private static final byte[] EMPTY_RGBA = new byte[0];
    private static final int HUD_BIT_PROCESSES = 1 << 2;

    private long mBurstStart;
    private String mTopPackage;
    private int mTopTaskId = -1;
    private int mPantauTaskId = -1;
    @Nullable private Session mSession;
    private static final long PANTAU_WAKE_TIMEOUT_MS = 1500;
    private static final long PANTAU_WAKE_BACKOFF_MS = 3000;
    private final Object mPantauLock = new Object();
    @Nullable private volatile IPantau mPantau;
    @Nullable private volatile IBinder mHydratedBinder;
    private volatile boolean mPantauRegActive;
    private volatile int mHudEnabledMask;
    private volatile long mLastPantauWakeFailMs;
    @Nullable private String mLastPushedProfile;
    private long mLastDispKey = Long.MIN_VALUE;
    private long mLastNetKey = Long.MIN_VALUE;
    @Nullable private NetworkCapabilities mLastNetCaps;

    private final Runnable mReleasePantau = () -> {
        synchronized (mPantauLock) {
            if (mSession == null && mHudEnabledMask == 0 && mPantau != null) {
                mPantau = null;
                mHydratedBinder = null;
                mPantauTaskId = -1;
                mLastPushedProfile = null;
                mLastDispKey = Long.MIN_VALUE;
                mLastNetKey = Long.MIN_VALUE;
                mPushedUids.clear();
                mPushedLabels.clear();
                Binder.flushPendingCommands();
            }
        }
    };

    private final IServiceCallback mPantauRegCb = new IServiceCallback.Stub() {
        @Override
        public void onRegistration(String name, IBinder binder) {
            if (binder == null) return;
            mHandler.post(() -> {
                if (!binder.isBinderAlive()) return;
                final IPantau next = IPantau.Stub.asInterface(binder);
                mPantau = next;
                mLastPantauWakeFailMs = 0;
                hydratePantau(next);
            });
        }
    };

    /** The game in a live session, read lock-free by the window policy's gesture listener. */
    @Nullable private static volatile String sGamePackage;

    /**
     * The package of the game in a live Pentas session, or null. DisplayPolicy uses it so a
     * side swipe in that game is only a back gesture and never also reveals the system bars.
     */
    @Nullable
    public static String gamePackage() {
        return sGamePackage;
    }

    /** Package → render scale (never 1) for the current user, replaced whole on each change. */
    private static volatile Map<String, Float> sRenderScales = Collections.emptyMap();

    /**
     * The render scale of a game's SurfaceView buffer, 1 when Pentas sets none.
     * Read lock-free by WindowManager when it creates a window or reports its frames.
     */
    public static float renderScale(@Nullable String pkg) {
        final Float scale = pkg != null ? sRenderScales.get(pkg) : null;
        return scale != null ? scale : 1f;
    }
    private int mBindFailures;

    /** One bound session. */
    private final class Session implements ServiceConnection {
        final String pkg;
        final int mode;
        final int userId;
        final long since = SystemClock.uptimeMillis();
        boolean connected;

        Session(String pkg, int mode, int userId) {
            this.pkg = pkg;
            this.mode = mode;
            this.userId = userId;
        }

        Uri uri() {
            return new Uri.Builder().scheme("pentas").authority("session")
                    .appendPath(pkg).appendQueryParameter("mode", Integer.toString(mode)).build();
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            connected = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The UI process died; BIND_AUTO_CREATE restarts it and onBind runs again.
            connected = false;
        }
    }

    private final Runnable mSettle = () -> {
        mBurstStart = 0;
        evaluate();
    };

    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(PentasService.this::onStackEvent);
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            mHandler.post(PentasService.this::onStackEvent);
        }
    };

    public PentasService(@NonNull Context context) {
        mContext = context;
        mDisplayTouchBridge = new DeviceDisplayTouchBridge(context, mHandler);
        mThermalBridge = new ThermalPolicyBridge(context, mHandler);
        // A value left by a previous system_server would keep that game's one-swipe back.
        mHandler.post(() -> {
            try {
                Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_GAME_FOCUS,
                        "", UserHandle.USER_SYSTEM);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Couldn't clear the session game", e);
            }
        });

        final ContentObserver lists = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mModes.clear();
                evaluate();
            }
        };
        for (String key : new String[] {KEY_GAME_LIST, KEY_AUTO_DETECT, KEY_VIDEO_LIST}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, lists, UserHandle.USER_ALL);
        }
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(KEY_RENDER_SCALE), false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        loadRenderScales();
                    }
                }, UserHandle.USER_ALL);
        mHandler.post(this::loadRenderScales);

        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final Uri data = intent.getData();
                if (data != null) mModes.remove(data.getSchemeSpecificPart());
            }
        }, UserHandle.ALL, packages, null, mHandler);

        final IntentFilter users = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        users.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    mModes.clear();
                    endSession();
                    loadRenderScales();
                }
                mDisplayTouchBridge.onUserChanged();
                mThermalBridge.onUserChanged();
                evaluate();
            }
        }, UserHandle.ALL, users, null, mHandler);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_HUD_PROFILE), false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        final String raw = Settings.Secure.getStringForUser(
                                mContext.getContentResolver(), KEY_HUD_PROFILE,
                                ActivityManager.getCurrentUser());
                        mHudEnabledMask = parseEnabledMask(raw);
                        final boolean anyOn = mHudEnabledMask != 0;
                        final IPantau p = (anyOn && mSession != null && mSession.mode == MODE_GAME)
                                ? wakePantauAsync()
                                : peekPantau();
                        if (p != null) {
                            pushHudProfile(p, raw);
                        }
                        schedulePantauReleaseIfIdle();
                    }
                }, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        pushMonetPalette(peekPantau());
                    }
                }, UserHandle.USER_ALL);

        // Foreground broadcasts (SCREEN_ON/OFF, 10s ANR timeout) must not share BackgroundThread
        // with unrelated services or APK icon loads; dispatch on FgThread and bounce to mHandler.
        final IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                mHandler.post(() -> {
                    mDisplayTouchBridge.onScreenStateChanged(on);
                    mThermalBridge.onScreenStateChanged(on);
                    final IPantau p = peekPantau();
                    if (p == null) return;
                    try {
                        p.event(7 /* ScreenState */, on ? 1L : 0L, 0L);
                    } catch (RemoteException e) {
                        mPantau = null;
                    }
                });
            }
        }, UserHandle.ALL, screen, null, FgThread.getHandler());

        mHandler.post(() -> {
            seedDeviceConfigDefaults();
            mDisplayTouchBridge.start();
            mThermalBridge.start();
            mHudEnabledMask = parseEnabledMask(Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), KEY_HUD_PROFILE,
                    ActivityManager.getCurrentUser()));
            try {
                ServiceManager.registerForNotifications(PANTAU_DAEMON_SERVICE, mPantauRegCb);
                mPantauRegActive = true;
            } catch (Exception e) {
                Slog.w(TAG, "registerForNotifications(" + PANTAU_DAEMON_SERVICE + ") failed", e);
            }
            try {
                ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
            } catch (Exception e) {
                Slog.e(TAG, "Can't watch tasks; sidebars are off", e);
            }
            final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            if (dm != null) {
                dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                    @Override public void onDisplayAdded(int id) {}
                    @Override public void onDisplayRemoved(int id) {}
                    @Override
                    public void onDisplayChanged(int id) {
                        if (id == Display.DEFAULT_DISPLAY) {
                            pushDisplayState(peekPantau());
                            mDisplayTouchBridge.onDisplayChanged();
                        }
                    }
                }, mHandler, DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                        | DisplayManager.EVENT_TYPE_DISPLAY_REFRESH_RATE);
            }
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                try {
                    cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                            mLastNetCaps = nc;
                            pushNetworkMeta(peekPantau(), nc);
                        }

                        @Override
                        public void onLost(Network network) {
                            mLastNetCaps = null;
                            pushNetworkMeta(peekPantau(), null);
                        }
                    }, mHandler);
                } catch (Exception e) {
                    Slog.w(TAG, "registerDefaultNetworkCallback failed", e);
                }
            }
        });
    }

    @Nullable
    String topPackage() {
        return mTopPackage;
    }

    int topTaskId() {
        return mTopTaskId;
    }

    /** Parses {@code en=<mask>} from the canonical {@code pentas_hud} wire profile without regex/allocs. */
    private static int parseEnabledMask(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        final int n = raw.length();
        int i = 0;
        while (i < n) {
            if (i + 3 <= n && raw.charAt(i) == 'e' && raw.charAt(i + 1) == 'n'
                    && raw.charAt(i + 2) == '=' && (i == 0 || raw.charAt(i - 1) == ';')) {
                int j = i + 3;
                int v = 0;
                while (j < n) {
                    final char c = raw.charAt(j);
                    if (c < '0' || c > '9') break;
                    v = v * 10 + (c - '0');
                    j++;
                }
                return v;
            }
            final int semi = raw.indexOf(';', i);
            if (semi < 0) break;
            i = semi + 1;
        }
        return 0;
    }

    /**
     * Returns an already-running {@code pantaud} binder without starting the lazy service.
     * Once {@link #mPantauRegCb} is registered with {@code servicemanager}, an offline daemon
     * returns {@code null} via a single volatile read (0 Binder IPCs on display/net/screen events).
     */
    @Nullable
    IPantau peekPantau() {
        final IPantau cur = mPantau;
        if (cur != null && cur.asBinder().isBinderAlive()) {
            if (cur.asBinder() != mHydratedBinder) hydratePantau(cur);
            return cur;
        }
        if (mPantauRegActive) {
            if (cur != null) {
                mPantau = null;
                mHydratedBinder = null;
            }
            return null;
        }
        final IBinder b = ServiceManager.checkService(PANTAU_DAEMON_SERVICE);
        if (b == null) {
            mPantau = null;
            mHydratedBinder = null;
            return null;
        }
        final IPantau next = IPantau.Stub.asInterface(b);
        mPantau = next;
        hydratePantau(next);
        return next;
    }

    /**
     * Sends a single non-blocking {@code IServiceManager.getService2} RPC directly to
     * {@code servicemanager} (handle 0), triggering {@code tryStartService} for lazy AIDL
     * services without Java {@code ServiceManagerProxy}'s {@code checkService2} redirect and
     * without {@code waitForService}'s unbounded blocking loop.
     */
    @Nullable
    private static IBinder triggerLazyStart() {
        try {
            final IServiceManager sm = IServiceManager.Stub.asInterface(
                    Binder.allowBlocking(BinderInternal.getContextObject()));
            if (sm == null) return null;
            final Service svc = sm.getService2(PANTAU_DAEMON_SERVICE);
            if (svc == null || svc.getTag() != Service.serviceWithMetadata) return null;
            return Binder.allowBlocking(svc.getServiceWithMetadata().service);
        } catch (Throwable t) {
            Slog.w(TAG, "triggerLazyStart(" + PANTAU_DAEMON_SERVICE + ") failed", t);
            return null;
        }
    }

    /**
     * Triggers lazy startup of {@code pantaud} without blocking {@link BackgroundThread}.
     * If {@code pantaud} is still starting, {@link #mPantauRegCb} hydrates it as soon as it
     * registers with {@code servicemanager} (~25 ms).
     */
    @Nullable
    IPantau wakePantauAsync() {
        final IPantau existing = peekPantau();
        if (existing != null) return existing;
        final long now = SystemClock.uptimeMillis();
        final long lastFail = mLastPantauWakeFailMs;
        if (lastFail > 0 && now - lastFail < PANTAU_WAKE_BACKOFF_MS) {
            return null;
        }
        final IBinder b = triggerLazyStart();
        if (b == null) {
            mLastPantauWakeFailMs = now;
            return null;
        }
        mLastPantauWakeFailMs = 0;
        final IPantau next = IPantau.Stub.asInterface(b);
        mPantau = next;
        hydratePantau(next);
        return next;
    }

    /**
     * Lazily starts and connects to {@code pantaud} with a strict bounded deadline (for CLI
     * binder threads such as {@code cmd pantau snap}). Never calls infinite {@code waitForService}.
     */
    @Nullable
    IPantau requirePantau() {
        final IPantau existing = peekPantau();
        if (existing != null) return existing;
        IBinder b = triggerLazyStart();
        if (b == null) {
            final long deadline = SystemClock.uptimeMillis() + PANTAU_WAKE_TIMEOUT_MS;
            while (SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(30);
                b = ServiceManager.checkService(PANTAU_DAEMON_SERVICE);
                if (b != null) break;
            }
        }
        if (b == null) {
            mLastPantauWakeFailMs = SystemClock.uptimeMillis();
            Slog.w(TAG, "pantaud unavailable after " + PANTAU_WAKE_TIMEOUT_MS + "ms");
            return null;
        }
        mLastPantauWakeFailMs = 0;
        final IPantau next = IPantau.Stub.asInterface(b);
        mPantau = next;
        hydratePantau(next);
        return next;
    }

    /** Pushes full display/theme/profile/network/icon state once per fresh {@code pantaud} instance. */
    private void hydratePantau(@NonNull IPantau p) {
        final IBinder binder = p.asBinder();
        synchronized (mPantauLock) {
            if (binder == mHydratedBinder) return;
            mHydratedBinder = binder;
            mPantauTaskId = -1;
            mPushedUids.clear();
            mPushedLabels.clear();
            mLastPushedProfile = null;
            mLastDispKey = Long.MIN_VALUE;
            mLastNetKey = Long.MIN_VALUE;
        }
        try {
            binder.linkToDeath(() -> mHandler.post(() -> {
                boolean shouldRewake = false;
                synchronized (mPantauLock) {
                    if (mHydratedBinder == binder) {
                        mPantau = null;
                        mHydratedBinder = null;
                        mPantauTaskId = -1;
                        mPushedUids.clear();
                        mPushedLabels.clear();
                        mLastPushedProfile = null;
                        mLastDispKey = Long.MIN_VALUE;
                        mLastNetKey = Long.MIN_VALUE;
                        // Only re-wake on unexpected crash while a game HUD is actually enabled!
                        // When en=0 and Sidebar closed, pantaud exits cleanly by design.
                        shouldRewake = (mSession != null && mSession.mode == MODE_GAME
                                && mHudEnabledMask != 0);
                    }
                }
                if (shouldRewake) {
                    wakePantauAsync();
                }
            }), 0);
        } catch (RemoteException ignored) {
        }
        // Parent PantauHudRoot under Display 0's Accessibility Overlays BEFORE creating HUD layers
        // so scrcpy & screenshots mirror the HUD whenever show_in_capture is enabled.
        pushHudParent(p);
        pushHudProfile(p, null);
        pushDisplayState(p);
        pushMonetPalette(p);
        pushNetworkMeta(p, mLastNetCaps);
        mHandler.post(() -> {
            if (p.asBinder() != mHydratedBinder || !p.asBinder().isBinderAlive()) return;
            if ((mHudEnabledMask & HUD_BIT_PROCESSES) != 0) {
                pushRunningAppIcons(p);
                pushAllInstalledUidLabels(p);
            }
            final Session s = mSession;
            if (s != null && mTopTaskId >= 0) {
                notifyPantauSession(mTopTaskId, uidOf(s.pkg, s.userId), s.mode == MODE_GAME);
            }
        });
    }

    private void pushHudParent(@Nullable IPantau p) {
        if (p == null) return;
        try {
            final WindowManagerInternal wmi = LocalServices.getService(WindowManagerInternal.class);
            if (wmi == null) return;
            // Shared WM layer inside Display 0 (Z = MAX_VALUE - 2) — do NOT release it!
            final SurfaceControl a11y = wmi.getA11yOverlayLayer(Display.DEFAULT_DISPLAY);
            if (a11y == null || !a11y.isValid()) return;
            final IBinder handle = extractSurfaceHandle(a11y);
            if (handle != null) {
                p.setHudParent(handle);
            }
        } catch (RemoteException e) {
            mPantau = null;
            mHydratedBinder = null;
        } catch (Exception e) {
            Slog.w(TAG, "pushHudParent failed", e);
        }
    }

    @Nullable
    private static IBinder extractSurfaceHandle(@NonNull SurfaceControl sc) {
        final Parcel tmp = Parcel.obtain();
        try {
            sc.writeToParcel(tmp, 0);
            tmp.setDataPosition(0);
            tmp.readInt(); // mWidth
            tmp.readInt(); // mHeight
            if (tmp.readInt() == 0) return null; // null native SurfaceControl
            tmp.readStrongBinder(); // ISurfaceComposerClient
            return tmp.readStrongBinder(); // mHandle
        } finally {
            tmp.recycle();
        }
    }

    private void pushNetworkMeta(@Nullable IPantau p, @Nullable NetworkCapabilities nc) {
        if (p == null) return;
        int linkMask = 0;
        int wifiStd = 0;
        int wifiFreq = 0;
        int wifiMbps = 0;
        int wifiRssi = 0;
        int cellGen = 0;
        int cellLevel = 0;
        int cellRsrp = 0;

        try {
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            if (nc == null && cm != null) {
                final Network active = cm.getActiveNetwork();
                if (active != null) nc = cm.getNetworkCapabilities(active);
            }
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) linkMask |= 1;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) linkMask |= 2;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) linkMask |= 4;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) linkMask |= 8;
            }
            // Under WireGuard/VPN, the active NetworkCapabilities may report only TRANSPORT_VPN;
            // probe all connected networks so Wi-Fi/5G/4G transport & dBm metadata stays populated.
            if (cm != null && (linkMask & (1 | 2 | 4)) == 0) {
                for (Network net : cm.getAllNetworks()) {
                    final NetworkCapabilities c = cm.getNetworkCapabilities(net);
                    if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        continue;
                    }
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) linkMask |= 1;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) linkMask |= 2;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) linkMask |= 4;
                }
            }
            if ((linkMask & 1) != 0) {
                final WifiManager wm = mContext.getSystemService(WifiManager.class);
                @SuppressWarnings("deprecation")
                final WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                if (wi != null && wi.getNetworkId() != -1) {
                    wifiFreq = wi.getFrequency();
                    wifiMbps = wi.getLinkSpeed();
                    wifiRssi = wi.getRssi();
                    wifiStd = switch (wi.getWifiStandard()) {
                        case ScanResult.WIFI_STANDARD_11BE -> 7;
                        case ScanResult.WIFI_STANDARD_11AX -> 6;
                        case ScanResult.WIFI_STANDARD_11AC -> 5;
                        case ScanResult.WIFI_STANDARD_11N -> 4;
                        default -> 0;
                    };
                }
            }
            if ((linkMask & 2) != 0) {
                TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
                if (tm != null) {
                    final int dataSub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
                    if (dataSub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        tm = tm.createForSubscriptionId(dataSub);
                    }
                    final int nt = tm.getDataNetworkType();
                    cellGen = switch (nt) {
                        case TelephonyManager.NETWORK_TYPE_NR -> 5;
                        case TelephonyManager.NETWORK_TYPE_LTE,
                             TelephonyManager.NETWORK_TYPE_IWLAN -> 4;
                        case TelephonyManager.NETWORK_TYPE_UMTS,
                             TelephonyManager.NETWORK_TYPE_HSDPA,
                             TelephonyManager.NETWORK_TYPE_HSUPA,
                             TelephonyManager.NETWORK_TYPE_HSPA,
                             TelephonyManager.NETWORK_TYPE_HSPAP -> 3;
                        case TelephonyManager.NETWORK_TYPE_GPRS,
                             TelephonyManager.NETWORK_TYPE_EDGE -> 2;
                        default -> 0;
                    };
                    final SignalStrength ss = tm.getSignalStrength();
                    if (ss != null) {
                        cellLevel = ss.getLevel();
                        for (CellSignalStrength cs : ss.getCellSignalStrengths()) {
                            final int dbm = cs.getDbm();
                            if (dbm < 0 && dbm > -160) {
                                cellRsrp = dbm;
                                break;
                            }
                        }
                    }
                }
            }
            final long netKey = ((long) (linkMask & 0xff) << 56)
                    ^ ((long) (wifiStd & 0xf) << 52)
                    ^ ((long) (wifiFreq & 0x3fff) << 38)
                    ^ ((long) (wifiMbps & 0xfff) << 26)
                    ^ ((long) (wifiRssi & 0xff) << 18)
                    ^ ((long) (cellGen & 0xf) << 14)
                    ^ ((long) (cellLevel & 0xf) << 10)
                    ^ (cellRsrp & 0x3ff);
            if (netKey == mLastNetKey) return;
            mLastNetKey = netKey;
            p.pushNetMeta(linkMask, wifiStd, wifiFreq, wifiMbps, wifiRssi, cellGen, cellLevel, cellRsrp);
        } catch (RemoteException e) {
            mPantau = null;
            mHydratedBinder = null;
            mPushedUids.clear();
        } catch (Exception e) {
            Slog.w(TAG, "pushNetworkMeta failed", e);
        }
    }

    /**
     * Pushes label-only entries (0-byte rgba, oneway) for installed app UIDs once per pantaud
     * instance — ONLY when the Processes HUD is enabled. Labels come from {@link #mUidLabelCache}
     * so repeated daemon wakes never reload APK resources.
     */
    private void pushAllInstalledUidLabels(@Nullable IPantau p) {
        if (p == null) return;
        try {
            final PackageManager pm = mContext.getPackageManager();
            final int user = ActivityManager.getCurrentUser();
            final java.util.List<ApplicationInfo> apps = pm.getInstalledApplicationsAsUser(0, user);
            if (apps == null) return;
            int pushed = 0;
            for (ApplicationInfo ai : apps) {
                if (pushed >= 240) break;
                final int uid = ai.uid;
                if (uid < 10000 || mPushedUids.contains(uid) || mPushedLabels.contains(uid)) continue;
                String label = mUidLabelCache.get(uid);
                if (label == null) {
                    final CharSequence cs = pm.getApplicationLabel(ai);
                    label = (cs != null && cs.length() > 0) ? cs.toString() : ai.packageName;
                    if (label == null || label.isEmpty()) continue;
                    if (mUidLabelCache.size() >= 512) mUidLabelCache.clear();
                    mUidLabelCache.put(uid, label);
                }
                p.pushUidMeta(uid, label, EMPTY_RGBA);
                mPushedLabels.add(uid);
                pushed++;
            }
        } catch (RemoteException e) {
            mPantau = null;
            mHydratedBinder = null;
            mPushedUids.clear();
            mPushedLabels.clear();
        } catch (Exception e) {
            Slog.w(TAG, "pushAllInstalledUidLabels failed", e);
        }
    }

    private void pushRunningAppIcons(@Nullable IPantau p) {
        if (p == null) return;
        try {
            final ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) return;
            final java.util.List<ActivityManager.RunningAppProcessInfo> procs =
                    am.getRunningAppProcesses();
            if (procs == null) return;
            final int user = ActivityManager.getCurrentUser();
            int pushed = 0;
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (pushed >= 32) break;
                if (info.uid < 10000 || info.pkgList == null || info.pkgList.length == 0) continue;
                if (mPushedUids.contains(info.uid)) continue;
                if (pushUidIcon(p, info.uid, info.pkgList[0], user)) {
                    pushed++;
                }
            }
            @SuppressWarnings("deprecation")
            final java.util.List<ActivityManager.RunningServiceInfo> svcs = am.getRunningServices(64);
            if (svcs != null) {
                for (ActivityManager.RunningServiceInfo svc : svcs) {
                    if (pushed >= 32) break;
                    if (svc.uid < 10000 || svc.service == null) continue;
                    if (mPushedUids.contains(svc.uid)) continue;
                    if (pushUidIcon(p, svc.uid, svc.service.getPackageName(), user)) {
                        pushed++;
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "pushRunningAppIcons failed", e);
        }
    }

    private boolean pushUidIcon(@Nullable IPantau p, int uid, String pkg, int user) {
        if (p == null || uid <= 0 || pkg == null || mPushedUids.contains(uid)) return false;
        try {
            final PackageManager pm = mContext.getPackageManager();
            final ApplicationInfo ai = pm.getApplicationInfoAsUser(pkg, 0, user);
            String label = mUidLabelCache.get(uid);
            if (label == null) {
                final CharSequence cs = pm.getApplicationLabel(ai);
                label = (cs != null && cs.length() > 0) ? cs.toString() : pkg;
                if (mUidLabelCache.size() >= 128) mUidLabelCache.clear();
                mUidLabelCache.put(uid, label);
            }
            byte[] rgba = EMPTY_RGBA;
            try {
                final Drawable d = pm.getApplicationIcon(ai);
                final int dim = 28;
                final Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
                final Canvas c = new Canvas(bmp);
                final Path clip = new Path();
                clip.addRoundRect(new RectF(0, 0, dim, dim), 7f, 7f, Path.Direction.CW);
                c.clipPath(clip);
                d.setBounds(0, 0, dim, dim);
                d.draw(c);
                final ByteBuffer bb = ByteBuffer.allocate(dim * dim * 4);
                bmp.copyPixelsToBuffer(bb);
                bmp.recycle();
                rgba = bb.array();
            } catch (Exception ignored) {
                // Still push the friendly label even if icon rendering fails.
            }
            p.pushUidMeta(uid, label, rgba);
            mPushedUids.add(uid);
            return true;
        } catch (RemoteException e) {
            mPantau = null;
            mHydratedBinder = null;
            mPushedUids.clear();
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    void pushDisplayState(@Nullable IPantau p) {
        if (p == null) return;
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        final Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (d == null) return;
        final DisplayInfo info = new DisplayInfo();
        if (!d.getDisplayInfo(info)) return;
        final int hz = Math.round(d.getMode().getRefreshRate());
        final long key = ((long) (info.rotation & 0x3) << 48)
                | ((long) (info.logicalWidth & 0xffff) << 32)
                | ((long) (info.logicalHeight & 0xffff) << 16)
                | (hz & 0xffff);
        if (key == mLastDispKey) return;
        mLastDispKey = key;
        try {
            p.displayChanged(info.rotation, info.logicalWidth, info.logicalHeight, hz);
        } catch (RemoteException e) {
            mPantau = null;
            mHydratedBinder = null;
        }
    }

    void pushMonetPalette(@Nullable IPantau p) {
        if (p == null) return;
        try {
            final int[] argb = new int[] {
                    mContext.getColor(com.android.internal.R.color.system_neutral1_900),
                    mContext.getColor(com.android.internal.R.color.system_accent1_200),
                    mContext.getColor(com.android.internal.R.color.system_neutral1_100),
                    mContext.getColor(com.android.internal.R.color.system_neutral2_300),
            };
            p.setMonet(argb);
        } catch (Exception e) {
            Slog.w(TAG, "pushMonetPalette failed", e);
        }
    }

    void setHudProfileSetting(String raw) {
        mHudEnabledMask = parseEnabledMask(raw);
        Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_HUD_PROFILE, raw,
                ActivityManager.getCurrentUser());
        pushHudProfile(peekPantau(), raw);
    }

    private void pushHudProfile(@Nullable IPantau p, @Nullable String preloadedRaw) {
        if (p == null) return;
        final String raw = preloadedRaw != null ? preloadedRaw : Settings.Secure.getStringForUser(
                mContext.getContentResolver(), KEY_HUD_PROFILE, ActivityManager.getCurrentUser());
        final String nonNull = raw != null ? raw : "";
        mHudEnabledMask = parseEnabledMask(nonNull);
        if (nonNull.equals(mLastPushedProfile)) return;
        mLastPushedProfile = nonNull;
        try {
            p.configure(nonNull);
            // Warm running app icons & labels ONLY when Processes widget (bit 2) is enabled!
            if ((mHudEnabledMask & HUD_BIT_PROCESSES) != 0) {
                pushRunningAppIcons(p);
                if (mPushedLabels.isEmpty()) {
                    pushAllInstalledUidLabels(p);
                }
            }
        } catch (RemoteException e) {
            mPantau = null;
            mHydratedBinder = null;
            mLastPushedProfile = null;
            mPushedUids.clear();
            mPushedLabels.clear();
        }
    }

    private void notifyPantauSession(int taskId, int uid, boolean isGame) {
        try {
            // Zero overhead during gaming when all HUD widgets are off: do not wake pantaud until
            // either a HUD widget is enabled or the user opens the Sidebar!
            final boolean shouldWake = isGame && mHudEnabledMask != 0;
            final IPantau p = shouldWake ? wakePantauAsync() : peekPantau();
            if (p != null) {
                p.session(taskId, uid, isGame);
                mPantauTaskId = uid < 0 ? -1 : taskId;
                if ((mHudEnabledMask & HUD_BIT_PROCESSES) != 0) {
                    if (uid >= 10000 && mTopPackage != null) {
                        pushUidIcon(p, uid, mTopPackage, ActivityManager.getCurrentUser());
                    }
                    if (isGame) {
                        pushRunningAppIcons(p);
                    }
                }
            }
        } catch (RemoteException e) {
            mPantau = null;
            mHydratedBinder = null;
            mPantauTaskId = -1;
            mPushedUids.clear();
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new PantauShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    private void onStackEvent() {
        final long now = SystemClock.uptimeMillis();
        if (mBurstStart == 0) mBurstStart = now;
        mHandler.removeCallbacks(mSettle);
        final long cap = mBurstStart + SETTLE_CAP_MS;
        mHandler.postAtTime(mSettle, Math.min(now + SETTLE_MS, cap));
    }

    private void evaluate() {
        final RootTaskInfo top;
        try {
            top = frontTask();
        } catch (Exception e) {
            Slog.w(TAG, "Focused task query failed", e);
            return;
        }
        final String pkg = top != null && top.topActivity != null
                ? top.topActivity.getPackageName() : null;
        final int type = top != null ? top.getActivityType() : ACTIVITY_TYPE_HOME;
        mTopPackage = pkg;
        mTopTaskId = top != null ? top.taskId : -1;
        mThermalBridge.onForegroundChanged(pkg != null ? pkg : "");

        final int user = ActivityManager.getCurrentUser();
        final int mode = pkg == null || type == ACTIVITY_TYPE_HOME || type == ACTIVITY_TYPE_RECENTS
                ? MODE_NONE : modeOf(pkg, user);

        if (mode == MODE_NONE) {
            endSession();
            // If HUD is pinned after a game, keep focused task updated for live FPS.
            if (mTopTaskId >= 0 && pkg != null && peekPantau() != null) {
                notifyPantauSession(mTopTaskId, uidOf(pkg, user), false);
            }
            return;
        }
        final Session current = mSession;
        if (current != null && current.pkg.equals(pkg) && current.mode == mode
                && current.userId == user) {
            // Trampoline/splash activities (e.g. Unity games like MLBB) spawn a fresh taskId under
            // the same package; keep pantaud's SurfaceFlinger FPS listener tracking the live task!
            if (mTopTaskId >= 0 && mTopTaskId != mPantauTaskId) {
                notifyPantauSession(mTopTaskId, uidOf(pkg, user), mode == MODE_GAME);
            }
            return;
        }
        startSession(pkg, mode, user);
    }

    /**
     * The task the session follows: the focused one, unless that is a floating window over
     * another app, in which case the topmost visible fullscreen task.
     */
    @Nullable
    private static RootTaskInfo frontTask() throws RemoteException {
        final RootTaskInfo focused = ActivityTaskManager.getService().getFocusedRootTaskInfo();
        if (focused == null) return null;
        final int mode = focused.getWindowingMode();
        if (mode != WINDOWING_MODE_FREEFORM && mode != WINDOWING_MODE_PINNED) return focused;
        for (RootTaskInfo t : ActivityTaskManager.getService()
                .getAllRootTaskInfosOnDisplay(focused.displayId)) {
            if (t.visible && t.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) return t;
        }
        return null;
    }

    private void startSession(String pkg, int mode, int user) {
        final Session next = new Session(pkg, mode, user);
        final Intent intent = new Intent().setComponent(SESSION_SERVICE).setData(next.uri());
        boolean bound;
        try {
            bound = mContext.bindServiceAsUser(intent, next,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                    UserHandle.of(user));
        } catch (Exception e) {
            Slog.w(TAG, "Bind failed for " + pkg, e);
            bound = false;
        }
        if (!bound) {
            // Pentas missing, disabled or the user still locked: nothing to show.
            if (mBindFailures++ < 3) Slog.w(TAG, "SessionService not available for " + pkg);
            mContext.unbindService(next);
            return;
        }
        mBindFailures = 0;
        // New first, old after, so the UI process never drops to cached between the two.
        final Session previous = mSession;
        mSession = next;
        setGame(mode == MODE_GAME ? pkg : null, user);
        if (mode == MODE_GAME) {
            mHandler.removeCallbacks(mReleasePantau);
            notifyPantauSession(mTopTaskId, uidOf(pkg, user), true);
        }
        if (previous != null) mContext.unbindService(previous);
        Slog.i(TAG, "Session " + pkg + " mode=" + mode);
    }

    private int uidOf(String pkg, int user) {
        try {
            return mContext.getPackageManager().getPackageUidAsUser(pkg, 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * Ends at once rather than lingering: the sidebar is an overlay, and keeping the session
     * bound would leave it floating over the launcher. Unbinding only caches the UI process,
     * so coming straight back is a warm start.
     */
    private void endSession() {
        final Session s = mSession;
        if (s == null) return;
        mSession = null;
        setGame(null, s.userId);
        if (s.mode == MODE_GAME) {
            notifyPantauSession(mTopTaskId, -1, false);
        }
        mContext.unbindService(s);
        schedulePantauReleaseIfIdle();
        Slog.i(TAG, "Session ended " + s.pkg);
    }

    private void schedulePantauReleaseIfIdle() {
        mHandler.removeCallbacks(mReleasePantau);
        if (mSession == null && mHudEnabledMask == 0 && mPantau != null) {
            mHandler.postDelayed(mReleasePantau, PANTAU_IDLE_RELEASE_MS);
        }
    }

    /**
     * Seeds Yasvara privacy, media, and autofill DeviceConfig defaults once if unset.
     * Replaces the standalone SimpleDeviceConfig APK with zero runtime process overhead.
     */
    private static void seedDeviceConfigDefaults() {
        final String[][] defaults = {
            {"privacy", "location_indicators_enabled", "true"},
            {"privacy", "location_indicator_settings_enabled", "true"},
            {"privacy", "discrete_history_ops_cslist",
                "0,109,1,108,26,101,27,102,106,100,8,9,6,7,4,5,62,59,60,90,77,111,114,112,"
                + "51,65,13,6,7,52,53,54,69,74,79,113,20,16,14,19,18,57"},
            {"attention_manager_service", "keep_screen_on_enabled", "false"},
            {"mediaprovider", "allowed_cloud_providers",
                "com.google.android.apps.photos.cloudpicker"},
            {"autofill", "compat_mode_allowed_packages",
                "com.android.chrome[url_bar]:com.brave.browser[url_bar]:"
                + "com.brave.browser_beta[url_bar]:com.brave.browser_nightly[url_bar]:"
                + "com.chrome.beta[url_bar]:com.chrome.dev[url_bar]:com.chrome.canary[url_bar]:"
                + "com.microsoft.emmx[url_bar]:com.microsoft.emmx.beta[url_bar]:"
                + "com.microsoft.emmx.canary[url_bar]:com.microsoft.emmx.dev[url_bar]:"
                + "com.opera.browser[url_field]:com.opera.browser.beta[url_bar]:"
                + "com.opera.mini.native[url_bar]:com.opera.mini.native.beta[url_bar]:"
                + "com.sec.android.app.sbrowser[location_bar_edit_text]:"
                + "com.sec.android.app.sbrowser.beta[location_bar_edit_text]:"
                + "org.mozilla.fennec_aurora[url_bar]:org.mozilla.firefox[url_bar]:"
                + "org.mozilla.firefox_beta[url_bar]:org.chromium.chrome[url_bar]"},
        };
        for (String[] entry : defaults) {
            try {
                if (DeviceConfig.getProperty(entry[0], entry[1]) == null) {
                    DeviceConfig.setProperty(entry[0], entry[1], entry[2], false);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "seedDeviceConfigDefaults failed for "
                        + entry[0] + "/" + entry[1], t);
            }
        }
    }

    /** Publishes the session game; a write only when it changes, so a few per session. */
    private void setGame(@Nullable String pkg, int user) {
        final String old = sGamePackage;
        sGamePackage = pkg;
        if (pkg == null ? old == null : pkg.equals(old)) return;
        try {
            Settings.Secure.putStringForUser(mContext.getContentResolver(), KEY_GAME_FOCUS,
                    pkg == null ? "" : pkg, user);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Couldn't publish the session game", e);
        }
    }

    /** Reloads the current user's render scales and pushes every changed one to live windows. */
    private void loadRenderScales() {
        final String json = Settings.System.getStringForUser(mContext.getContentResolver(),
                KEY_RENDER_SCALE, ActivityManager.getCurrentUser());
        final ArrayMap<String, Float> next = new ArrayMap<>();
        if (json != null && !json.isEmpty()) {
            try {
                final JSONObject obj = new JSONObject(json);
                for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                    final String pkg = it.next();
                    final float scale = (float) obj.optDouble(pkg, 1);
                    if (scale != 1f && scale >= RENDER_SCALE_MIN && scale <= RENDER_SCALE_MAX) {
                        next.put(pkg, scale);
                    }
                }
            } catch (JSONException e) {
                Slog.w(TAG, "Bad " + KEY_RENDER_SCALE + ", render scales off", e);
            }
        }
        final Map<String, Float> previous = sRenderScales;
        sRenderScales = Collections.unmodifiableMap(next);

        final ArraySet<String> changed = new ArraySet<>(previous.keySet());
        changed.addAll(next.keySet());
        final WindowManagerInternal wm = LocalServices.getService(WindowManagerInternal.class);
        for (int i = 0; i < changed.size(); i++) {
            final String pkg = changed.valueAt(i);
            if (wm != null && !Objects.equals(previous.get(pkg), next.get(pkg))) {
                wm.updateSurfaceScaleForPackage(pkg);
            }
        }
    }

    private int modeOf(String pkg, int user) {
        if (PACKAGE.equals(pkg) || "android".equals(pkg) || "com.android.systemui".equals(pkg)) {
            return MODE_NONE;
        }
        final Integer cached = mModes.get(pkg);
        if (cached != null) return cached;
        final Integer mode = classify(pkg, user);
        // A failed lookup is not cached: it may be transient (package mid-install).
        if (mode == null) return MODE_NONE;
        mModes.put(pkg, mode);
        return mode;
    }

    @Nullable
    private Integer classify(String pkg, int user) {
        final String games = Settings.System.getStringForUser(
                mContext.getContentResolver(), KEY_GAME_LIST, user);
        if (games != null) {
            for (String entry : games.split(";")) {
                final int eq = entry.indexOf('=');
                final String name = eq >= 0 ? entry.substring(0, eq) : entry;
                if (pkg.equals(name)) return MODE_GAME;
            }
        }
        final ApplicationInfo info;
        try {
            info = mContext.getPackageManager().getApplicationInfoAsUser(pkg, 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        final boolean autoDetect = Settings.System.getIntForUser(
                mContext.getContentResolver(), KEY_AUTO_DETECT, 1, user) != 0;
        @SuppressWarnings("deprecation")
        final boolean isGame = info.category == ApplicationInfo.CATEGORY_GAME
                || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        if (autoDetect && isGame) return MODE_GAME;

        final String videos = Settings.System.getStringForUser(
                mContext.getContentResolver(), KEY_VIDEO_LIST, user);
        if (videos != null) {
            for (String entry : videos.split(";")) {
                final int eq = entry.indexOf('=');
                if (eq > 0 && pkg.equals(entry.substring(0, eq))) {
                    return "1".equals(entry.substring(eq + 1)) ? MODE_VIDEO : MODE_NONE;
                }
            }
        }
        if (info.category == ApplicationInfo.CATEGORY_VIDEO
                || info.category == ApplicationInfo.CATEGORY_AUDIO) {
            return MODE_VIDEO;
        }
        final String lower = pkg.toLowerCase(Locale.ROOT);
        for (String hint : VIDEO_HINTS) {
            if (lower.contains(hint)) return MODE_VIDEO;
        }
        return MODE_NONE;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        // Snapshot on the handler thread, which owns every field.
        final StringBuilder out = new StringBuilder();
        final boolean done = mHandler.runWithScissors(() -> {
            out.append("Pentas session control\n");
            out.append("  top: ").append(mTopPackage).append('\n');
            appendSession(out, "  session: ", mSession);
            out.append("  render scales: ").append(sRenderScales).append('\n');
            out.append("  cached modes: ").append(mModes.size()).append('\n');
            for (int i = 0; i < mModes.size(); i++) {
                final int mode = mModes.valueAt(i);
                if (mode != MODE_NONE) {
                    out.append("    ").append(mModes.keyAt(i)).append(" = ")
                            .append(mode == MODE_GAME ? "GAME" : "VIDEO").append('\n');
                }
            }
        }, 1000);
        pw.print(done ? out : "Pentas: handler busy\n");
    }

    private static void appendSession(StringBuilder out, String label, @Nullable Session s) {
        out.append(label);
        if (s == null) {
            out.append("none\n");
            return;
        }
        out.append(s.pkg).append(" mode=").append(s.mode).append(" u").append(s.userId)
                .append(s.connected ? " connected" : " connecting")
                .append(" for ").append((SystemClock.uptimeMillis() - s.since) / 1000)
                .append("s\n");
    }
}
