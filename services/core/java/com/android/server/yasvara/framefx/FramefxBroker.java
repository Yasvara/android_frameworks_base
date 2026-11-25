/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.framefx;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * framefx channel broker: Pentas's per-game Luwes and Kelir settings, delivered to
 * that game's own processes.
 *
 * Each armed package gets one sealed shared-memory channel. Pentas writes sections here; the
 * broker rewrites the channel under a sequence lock and wakes the futex on its sequence word, so
 * a game sees a change within a frame without any property, file or binder call of its own. A
 * game process opens the channel once at loader init, and gets a status page it writes back for
 * Pentas to read.
 *
 * Arming is the uid marker in /data/misc/framefx/armed that the graphics loaders test once per
 * process: a package with any section is armed, one without is not. Sections from
 * FIRST_VIDEO_SECTION on arm through /data/misc/framefx/video instead, which only MediaCodec tests,
 * so an app filtered for its video never has its own UI through the present hook. Layouts mirror
 * frameworks/libs/framefx/include/framefx/channel.h.
 */
public final class FramefxBroker extends Binder {

    private static final String TAG = "framefx";
    public static final String SERVICE_NAME = "yasvara_framefx";
    private static final String DESCRIPTOR = "yasvara.framefx.IBroker";

    private static final int OPEN = 1;
    private static final int SET_SECTION = 2;
    private static final int GET_SECTION = 3;
    private static final int STATUS = 4;
    private static final int ALIH_OPEN = 5;
    private static final int ALIH_SCAN = 6;

    static final int OK = 0;
    static final int NOT_ARMED = -1;
    static final int BAD_ARGUMENT = -2;
    static final int NO_MEMORY = -3;

    static final int CHANNEL_MAGIC = 0x43584646; // "FFXC"
    static final int CHANNEL_VERSION = 1;
    static final int CHANNEL_SIZE = 4096;
    static final int MAX_SECTIONS = 8;
    static final int TABLE_OFFSET = 16;
    static final int DATA_OFFSET = 80;
    static final int STATUS_SIZE = 4096;
    static final int MAX_SECTION_ID = 31;
    static final int FIRST_VIDEO_SECTION = 6;
    static final int LAST_VIDEO_SECTION = 7;
    /** Status pages kept per package; the oldest goes first. */
    private static final int MAX_PROCESSES = 8;

    // The text tap: a game's memory with org.yasvara.alih. Sizes mirror framefx/alih.h.
    private static final String ALIH_PACKAGE = "org.yasvara.alih";
    private static final String ALIH_DESCRIPTOR = "org.yasvara.alih.IService";
    private static final int ALIH_ATTACH = 1;
    private static final int ALIH_DETACH = 2;
    private static final int ALIH_FONT = 3;
    private static final String ALIH_SCAN_PROPERTY = "yasvara.alih.scan";
    private static final File ALIH_SCAN_DIR = new File("/data/misc/alih_fontd");
    private static final long ALIH_SCAN_POLL_MS = 1000;
    private static final int ALIH_SCAN_POLLS = 60;
    private static final int ALIH_RING_SIZE = 8 << 20;
    private static final int ALIH_TABLE_SIZE = 4096 + 2048 * 1024 + (2 << 20);
    private static final int LAPIS_SECTION = 3;
    private static final int LAPIS_FLAGS = 2;
    private static final int LAPIS_TEXT_TAP = 1 << 1;
    private static final byte[] LAPIS_DEFAULT = {1, 1, 0, 0};

    // Lepas: lepasd finds an armed game's fps cap from outside it and holds it at the target. The
    // game reads nothing of the section; arming it gives the present count lepasd times it by.
    private static final int LEPAS_SECTION = 8;
    private static final int LEPAS_MAJOR = 1;
    private static final String LEPAS_START_PROPERTY = "yasvara.lepas.start";
    private static final File LEPAS_DIR = new File("/data/misc/lepas");
    // QTI WindowManager: per-package refresh rate ahead of the app's own window request.
    private static final String FORCE_REFRESH_LIST = "ext_force_refresh_rate_list";

    private static final File MARKER_DIR = new File("/data/misc/framefx/armed");
    private static final File VIDEO_MARKER_DIR = new File("/data/misc/framefx/video");

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final File mStateDir = new File(Environment.getDataSystemDirectory(), "framefx");
    private final Object mLock = new Object();
    private final ArrayMap<String, Channel> mChannels = new ArrayMap<>();
    // On the handler.
    private final ArrayList<AlihSession> mAlihSessions = new ArrayList<>();
    @Nullable private IBinder mAlihService;
    // One per binding: a dead binding's late callbacks must not touch the next one.
    @Nullable private ServiceConnection mAlihConnection;
    private boolean mAlihScanning;

    private static native boolean nativePublish(ByteBuffer channel, byte[] image);
    private static native boolean nativeSnapshot(ByteBuffer page, byte[] out);

    /** A game process's status page. */
    private static final class Page {
        final int pid;
        final int uid;
        final SharedMemory memory;
        final ByteBuffer map;

        Page(int pid, int uid, SharedMemory memory, ByteBuffer map) {
            this.pid = pid;
            this.uid = uid;
            this.memory = memory;
            this.map = map;
        }

        void close() {
            SharedMemory.unmap(map);
            memory.close();
        }
    }

    /** A game process's memory with Alih, until the process goes. */
    private final class AlihSession implements IBinder.DeathRecipient {
        final String packageName;
        final int pid;
        final int uid;
        final SharedMemory ring;
        final SharedMemory table;
        final IBinder token;

        AlihSession(String packageName, int pid, int uid, SharedMemory ring, SharedMemory table,
                IBinder token) {
            this.packageName = packageName;
            this.pid = pid;
            this.uid = uid;
            this.ring = ring;
            this.table = table;
            this.token = token;
        }

        @Override
        public void binderDied() {
            mHandler.post(() -> alihDetach(this));
        }
    }

    private final class AlihConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mAlihConnection != this) return;
            mAlihService = service;
            for (AlihSession session : mAlihSessions) alihSend(session);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mAlihConnection == this) mAlihService = null;
        }

        // Force-stopped or updated: this binding never comes back, a new one starts it again.
        @Override
        public void onBindingDied(ComponentName name) {
            if (mAlihConnection != this) return;
            alihUnbind();
            if (!mAlihSessions.isEmpty()) alihBind(mAlihSessions.get(0).uid);
        }
    }

    /** One armed package. */
    private static final class Channel {
        final String packageName;
        final SparseArray<byte[]> sections = new SparseArray<>();
        @Nullable SharedMemory memory;
        @Nullable ByteBuffer map;
        final ArrayList<Page> pages = new ArrayList<>();

        Channel(String packageName) {
            this.packageName = packageName;
        }
    }

    public FramefxBroker(Context context) {
        mContext = context;
        attachInterface(null, DESCRIPTOR);
        mHandler.post(this::load);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final String pkg = intent.getData() != null
                        ? intent.getData().getSchemeSpecificPart() : null;
                if (pkg == null) return;
                if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                    forget(pkg);
                } else {
                    synchronized (mLock) {
                        final Channel channel = mChannels.get(pkg);
                        if (channel != null) writeMarkers(channel);
                    }
                }
            }
        }, UserHandle.ALL, filter, null, mHandler);
    }

    @Override
    protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply,
            int flags) throws RemoteException {
        if (code < OPEN || code > ALIH_SCAN) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(DESCRIPTOR);
        if (reply == null) return true;
        final int uid = Binder.getCallingUid();
        if (code == OPEN) {
            open(uid, Binder.getCallingPid(), reply);
            return true;
        }
        if (code == ALIH_OPEN) {
            alihOpen(uid, Binder.getCallingPid(), data.readStrongBinder(), reply);
            return true;
        }
        if (code == ALIH_SCAN) {
            final int pid = data.readInt();
            final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
            if (packages == null || !java.util.Arrays.asList(packages).contains(ALIH_PACKAGE)) {
                throw new SecurityException("framefx broker: alih only");
            }
            mHandler.post(() -> alihScan(pid));
            reply.writeInt(OK);
            return true;
        }
        if (UserHandle.getAppId(uid) != Process.SYSTEM_UID) {
            throw new SecurityException("framefx broker: system only");
        }
        final String pkg = data.readString();
        final long token = Binder.clearCallingIdentity();
        try {
            switch (code) {
                case SET_SECTION:
                    reply.writeInt(setSection(pkg, data.readInt(), data.createByteArray()));
                    break;
                case GET_SECTION: {
                    final byte[] bytes = getSection(pkg, data.readInt());
                    reply.writeInt(bytes != null ? OK : NOT_ARMED);
                    reply.writeByteArray(bytes != null ? bytes : new byte[0]);
                    break;
                }
                default:
                    status(pkg, reply);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return true;
    }

    /**
     * adb only: {@code cmd yasvara_framefx luwes <package> off|2|3|4 [extrapolate|interpolate]
     * [hud|nohud]}, {@code luwes <package> disarm}, {@code kelir <package> <section hex>|off} or
     * {@code video-kelir <package> <section hex>|off} or {@code video <package> <section hex>|off}.
     */
    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err, @NonNull String[] args,
            @Nullable ShellCallback callback, @NonNull ResultReceiver result) {
        final PrintWriter pw = new PrintWriter(new FileOutputStream(out), true);
        final int uid = Binder.getCallingUid();
        int code = BAD_ARGUMENT;
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            pw.println("shell only");
        } else if (args.length >= 3 && "alih".equals(args[0])) {
            final long token = Binder.clearCallingIdentity();
            try {
                code = setTextTap(args[1], "on".equals(args[2]));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            pw.println(code == OK ? "ok" : "failed " + code);
        } else if (args.length >= 3 && "lepas".equals(args[0])) {
            if ("status".equals(args[2])) {
                pw.print(lepasState(args[1]));
                code = OK;
            } else {
                final byte[] section = lepasSection(args[2]);
                if (section != null) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        code = setSection(args[1], LEPAS_SECTION, section);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                pw.println(code == OK ? "ok, takes effect at the game's next start" : "failed " + code);
            }
        } else if (args.length >= 3 && ("luwes".equals(args[0]) || "kelir".equals(args[0])
                || "video-kelir".equals(args[0]) || "video".equals(args[0]))) {
            final boolean luwes = "luwes".equals(args[0]);
            final byte[] section = luwes ? luwesSection(args) : kelirSection(args[2]);
            if (section != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    final int id = luwes ? 1 : "kelir".equals(args[0]) ? 2
                            : "video-kelir".equals(args[0]) ? 6 : 7;
                    code = setSection(args[1], id, section);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            pw.println(code == OK ? "ok" : "failed " + code);
        } else {
            pw.println("usage: luwes <package> off|2|3|4 [extrapolate|interpolate] [hud|nohud]");
            pw.println("       luwes <package> disarm");
            pw.println("       kelir <package> <section hex>|off");
            pw.println("       video-kelir <package> <section hex>|off");
            pw.println("       video <package> <section hex>|off");
            pw.println("       alih <package> on|off");
            pw.println("       lepas <package> <fps>|off|status");
        }
        result.send(code == OK ? 0 : -1, null);
    }

    /** Luwes section v1 from shell arguments; empty to disarm, null when malformed. */
    @Nullable
    private static byte[] luwesSection(String[] args) {
        if ("disarm".equals(args[2])) return new byte[0];
        final int multiplier;
        if ("off".equals(args[2])) {
            multiplier = 0;
        } else {
            try {
                multiplier = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (multiplier < 2 || multiplier > 4) return null;
        }
        int mode = 0;
        int flags = 1;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "extrapolate" -> mode = 0;
                case "interpolate" -> mode = 1;
                case "hud" -> flags = 1;
                case "nohud" -> flags = 0;
                default -> {
                    return null;
                }
            }
        }
        return new byte[] {1, (byte) multiplier, (byte) mode, (byte) flags, 0, 0, 0, 0};
    }

    /**
     * A Kelir section from hex; empty for off. The game validates it; this only checks it is
     * bytes of a size a section can have.
     */
    @Nullable
    private static byte[] kelirSection(String arg) {
        if ("off".equals(arg)) return new byte[0];
        if (arg.length() % 2 != 0 || arg.length() / 2 > 256) return null;
        final byte[] out = new byte[arg.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(arg.charAt(2 * i), 16);
            final int lo = Character.digit(arg.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    /** Lepas section v1 from a shell argument; empty for off, null when malformed. */
    @Nullable
    private static byte[] lepasSection(String arg) {
        if ("off".equals(arg)) return new byte[0];
        final int fps;
        try {
            fps = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return null;
        }
        if (fps < 30 || fps > 240) return null;
        return new byte[] {LEPAS_MAJOR, (byte) fps, 0, 0};
    }

    /**
     * An armed game process came up: lepasd times it, finds its cap (cached per version) and holds
     * it. init starts lepasd on the property; a running lepasd picks up the next value itself.
     */
    private void lepasStart(String pkg, int pid, int target) {
        if (target <= 0) return;
        long version = 0;
        try {
            version = mContext.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        SystemProperties.set(LEPAS_START_PROPERTY, pid + " " + target + " " + version + " " + pkg);
        Slog.i(TAG, "lepas: " + pkg + " pid " + pid + " to " + target);
    }

    /** The package's entry in WindowManager's forced refresh list; target 0 removes it. */
    private void forceRefresh(String pkg, int target) {
        final android.content.ContentResolver cr = mContext.getContentResolver();
        final String current = android.provider.Settings.System.getString(cr, FORCE_REFRESH_LIST);
        final StringBuilder next = new StringBuilder();
        if (current != null) {
            for (String pair : current.split(";")) {
                final String entry = pair.trim();
                if (entry.isEmpty() || entry.startsWith(pkg + ",")) continue;
                if (next.length() > 0) next.append(';');
                next.append(entry);
            }
        }
        if (target > 0) {
            if (next.length() > 0) next.append(';');
            next.append(pkg).append(',').append(target);
        }
        android.provider.Settings.System.putString(cr, FORCE_REFRESH_LIST,
                next.length() > 0 ? next.toString() : null);
    }

    /** What lepasd last wrote for the package, for shell and Pentas. */
    private static String lepasState(String pkg) {
        if (pkg.indexOf('/') >= 0) return "";
        try {
            return new String(Files.readAllBytes(new File(LEPAS_DIR, pkg + ".state").toPath()));
        } catch (IOException e) {
            return "no state\n";
        }
    }

    /** The Lapis section's text bit, its other fields kept; takes effect at the game's next start. */
    private int setTextTap(String pkg, boolean on) {
        final byte[] current = getSection(pkg, LAPIS_SECTION);
        final byte[] next = current != null && current.length == LAPIS_DEFAULT.length
                ? current.clone() : LAPIS_DEFAULT.clone();
        next[LAPIS_FLAGS] = (byte) (on ? next[LAPIS_FLAGS] | LAPIS_TEXT_TAP
                : next[LAPIS_FLAGS] & ~LAPIS_TEXT_TAP);
        return setSection(pkg, LAPIS_SECTION,
                java.util.Arrays.equals(next, LAPIS_DEFAULT) ? new byte[0] : next);
    }

    /** A game process that taps its text asks for the memory it shares with Alih. */
    private void alihOpen(int uid, int pid, @Nullable IBinder token, Parcel reply) {
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        String pkg = null;
        synchronized (mLock) {
            if (packages != null) {
                for (String candidate : packages) {
                    final Channel channel = mChannels.get(candidate);
                    final byte[] lapis = channel != null
                            ? channel.sections.get(LAPIS_SECTION) : null;
                    if (lapis != null && lapis.length > LAPIS_FLAGS
                            && (lapis[LAPIS_FLAGS] & LAPIS_TEXT_TAP) != 0) {
                        pkg = candidate;
                        break;
                    }
                }
            }
        }
        if (pkg == null || token == null) {
            reply.writeInt(NOT_ARMED);
            return;
        }
        final AlihSession session;
        try {
            session = new AlihSession(pkg, pid, uid,
                    SharedMemory.create("alih-ring", ALIH_RING_SIZE),
                    SharedMemory.create("alih-table", ALIH_TABLE_SIZE), token);
            token.linkToDeath(session, 0);
        } catch (ErrnoException | RemoteException e) {
            Slog.w(TAG, "alih for " + pkg + ": " + e);
            reply.writeInt(NO_MEMORY);
            return;
        }
        reply.writeInt(OK);
        reply.writeFileDescriptor(session.ring.getFileDescriptor());
        reply.writeFileDescriptor(session.table.getFileDescriptor());
        Slog.i(TAG, "alih opened by " + pkg + " pid " + pid);
        mHandler.post(() -> alihAttach(session));
    }

    private void alihAttach(AlihSession session) {
        mAlihSessions.add(session);
        if (mAlihService != null) {
            alihSend(session);
        } else if (mAlihConnection == null) {
            alihBind(session.uid);
        }
    }

    private void alihBind(int uid) {
        final Intent intent = new Intent().setClassName(ALIH_PACKAGE, ALIH_PACKAGE + ".AlihService");
        final AlihConnection connection = new AlihConnection();
        mAlihConnection = connection;
        if (!mContext.bindServiceAsUser(intent, connection,
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND, mHandler,
                UserHandle.getUserHandleForUid(uid))) {
            Slog.w(TAG, "alih: no service to bind");
            alihUnbind();
        }
    }

    private void alihUnbind() {
        if (mAlihConnection != null) mContext.unbindService(mAlihConnection);
        mAlihConnection = null;
        mAlihService = null;
    }

    private void alihSend(AlihSession session) {
        final Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(ALIH_DESCRIPTOR);
            data.writeString(session.packageName);
            data.writeInt(session.pid);
            data.writeFileDescriptor(session.ring.getFileDescriptor());
            data.writeFileDescriptor(session.table.getFileDescriptor());
            mAlihService.transact(ALIH_ATTACH, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Slog.w(TAG, "alih attach: " + e);
        } finally {
            data.recycle();
        }
    }

    /** Finds the fonts of a game process Alih is reading, and hands them over. */
    private void alihScan(int pid) {
        AlihSession session = null;
        for (AlihSession candidate : mAlihSessions) {
            if (candidate.pid == pid) session = candidate;
        }
        if (session == null || mAlihScanning) return;
        mAlihScanning = true;
        final File dir = new File(ALIH_SCAN_DIR, Integer.toString(pid));
        deleteTree(dir);
        final long token = Binder.clearCallingIdentity();
        try {
            SystemProperties.set(ALIH_SCAN_PROPERTY, "");
            SystemProperties.set(ALIH_SCAN_PROPERTY, Integer.toString(pid));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        Slog.i(TAG, "alih: scanning " + session.packageName + " pid " + pid);
        final AlihSession target = session;
        mHandler.postDelayed(() -> alihScanPoll(target, dir, ALIH_SCAN_POLLS), ALIH_SCAN_POLL_MS);
    }

    private void alihScanPoll(AlihSession session, File dir, int left) {
        if (!new File(dir, "done").exists()) {
            if (left > 0 && mAlihSessions.contains(session)) {
                mHandler.postDelayed(() -> alihScanPoll(session, dir, left - 1), ALIH_SCAN_POLL_MS);
            } else {
                mAlihScanning = false;
                deleteTree(dir);
            }
            return;
        }
        mAlihScanning = false;
        final File[] files = dir.listFiles();
        if (files != null && mAlihService != null) {
            for (File file : files) {
                if (!file.getName().endsWith(".ttf")) continue;
                alihSendFont(session, file);
            }
        }
        deleteTree(dir);
    }

    /** Sends a copy: the scan's files are labelled for the daemon and this process only. */
    private void alihSendFont(AlihSession session, File file) {
        final Parcel data = Parcel.obtain();
        try {
            final byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length == 0) return;
            try (SharedMemory font = SharedMemory.create("alih-font", bytes.length)) {
                final ByteBuffer map = font.mapReadWrite();
                try {
                    map.put(bytes);
                } finally {
                    SharedMemory.unmap(map);
                }
                font.setProtect(OsConstants.PROT_READ);
                data.writeInterfaceToken(ALIH_DESCRIPTOR);
                data.writeString(session.packageName);
                data.writeInt(session.pid);
                data.writeString(file.getName());
                data.writeFileDescriptor(font.getFileDescriptor());
                mAlihService.transact(ALIH_FONT, data, null, IBinder.FLAG_ONEWAY);
            }
        } catch (ErrnoException | IOException | RemoteException e) {
            Slog.w(TAG, "alih font: " + e);
        } finally {
            data.recycle();
        }
    }

    private static void deleteTree(File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) file.delete();
        }
        dir.delete();
    }

    private void alihDetach(AlihSession session) {
        mAlihSessions.remove(session);
        session.ring.close();
        session.table.close();
        if (mAlihService != null) {
            final Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(ALIH_DESCRIPTOR);
                data.writeInt(session.pid);
                mAlihService.transact(ALIH_DETACH, data, null, IBinder.FLAG_ONEWAY);
            } catch (RemoteException e) {
                Slog.w(TAG, "alih detach: " + e);
            } finally {
                data.recycle();
            }
        }
        if (mAlihSessions.isEmpty()) alihUnbind();
    }

    /** A game process asks for its channel; its uid says which package it is. */
    private void open(int uid, int pid, Parcel reply) {
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        synchronized (mLock) {
            Channel channel = null;
            if (packages != null) {
                for (String pkg : packages) {
                    channel = mChannels.get(pkg);
                    if (channel != null) break;
                }
            }
            if (channel == null || channel.memory == null) {
                reply.writeInt(NOT_ARMED);
                return;
            }
            final Page page;
            try {
                final SharedMemory memory = SharedMemory.create("framefx-status", STATUS_SIZE);
                page = new Page(pid, uid, memory, memory.mapReadOnly());
            } catch (ErrnoException e) {
                Slog.w(TAG, "status page for " + channel.packageName + ": " + e);
                reply.writeInt(NO_MEMORY);
                return;
            }
            prunePages(channel);
            if (channel.pages.size() >= MAX_PROCESSES) channel.pages.remove(0).close();
            channel.pages.add(page);
            reply.writeInt(OK);
            reply.writeFileDescriptor(channel.memory.getFileDescriptor());
            reply.writeFileDescriptor(page.memory.getFileDescriptor());
            Slog.i(TAG, "channel opened by " + channel.packageName + " pid " + pid);
            final byte[] lepas = channel.sections.get(LEPAS_SECTION);
            if (lepas != null) {
                final String pkg = channel.packageName;
                mHandler.post(() -> lepasStart(pkg, pid, lepas[1] & 0xff));
            }
        }
    }

    private int setSection(@Nullable String pkg, int id, @Nullable byte[] bytes) {
        if (pkg == null || pkg.isEmpty() || pkg.indexOf('/') >= 0 || id < 1
                || id > MAX_SECTION_ID || bytes == null) {
            return BAD_ARGUMENT;
        }
        synchronized (mLock) {
            Channel channel = mChannels.get(pkg);
            if (bytes.length == 0 && channel == null) return OK;
            if (channel == null) {
                channel = new Channel(pkg);
                mChannels.put(pkg, channel);
            }
            final byte[] previous = channel.sections.get(id);
            if (bytes.length == 0) {
                channel.sections.remove(id);
            } else {
                channel.sections.put(id, bytes.clone());
            }
            if (image(channel) == null) {
                restore(channel, id, previous);
                return BAD_ARGUMENT;
            }
            final int result = commit(channel);
            if (result != OK) {
                restore(channel, id, previous);
            } else if (id == LEPAS_SECTION) {
                final int target = bytes.length >= 2 ? bytes[1] & 0xff : 0;
                mHandler.post(() -> forceRefresh(pkg, target));
            }
            return result;
        }
    }

    private static void restore(Channel channel, int id, @Nullable byte[] previous) {
        if (previous != null) {
            channel.sections.put(id, previous);
        } else {
            channel.sections.remove(id);
        }
    }

    @Nullable
    private byte[] getSection(@Nullable String pkg, int id) {
        synchronized (mLock) {
            final Channel channel = pkg != null ? mChannels.get(pkg) : null;
            return channel != null ? channel.sections.get(id) : null;
        }
    }

    /** Live processes of a package: pid and status page each. */
    private void status(@Nullable String pkg, Parcel reply) {
        synchronized (mLock) {
            final Channel channel = pkg != null ? mChannels.get(pkg) : null;
            if (channel == null) {
                reply.writeInt(0);
                return;
            }
            prunePages(channel);
            reply.writeInt(channel.pages.size());
            for (Page page : channel.pages) {
                final byte[] bytes = new byte[STATUS_SIZE];
                reply.writeInt(page.pid);
                reply.writeByteArray(nativeSnapshot(page.map, bytes) ? bytes : new byte[0]);
            }
        }
    }

    /** Publishes the channel, persists it and arms or disarms the package. Holds mLock. */
    private int commit(Channel channel) {
        final boolean armed = channel.sections.size() > 0;
        if (armed && channel.memory == null) {
            try {
                final SharedMemory memory = SharedMemory.create("framefx-channel", CHANNEL_SIZE);
                channel.map = memory.mapReadWrite();
                // Games map it read-only; the broker's own mapping stays writable.
                memory.setProtect(OsConstants.PROT_READ);
                channel.memory = memory;
            } catch (ErrnoException e) {
                Slog.w(TAG, "channel for " + channel.packageName + ": " + e);
                if (channel.map != null) SharedMemory.unmap(channel.map);
                channel.map = null;
                return NO_MEMORY;
            }
        }
        if (channel.map != null) nativePublish(channel.map, image(channel));
        persist(channel);
        writeMarkers(channel);
        if (!armed) {
            // Running processes keep their mapping and have just read an empty channel.
            mChannels.remove(channel.packageName);
            for (Page page : channel.pages) page.close();
            if (channel.map != null) SharedMemory.unmap(channel.map);
            if (channel.memory != null) channel.memory.close();
        }
        return OK;
    }

    /** The channel's bytes, sequence word aside; null when the sections do not fit. */
    @Nullable
    static byte[] image(Channel channel) {
        return image(channel.sections);
    }

    @Nullable
    static byte[] image(SparseArray<byte[]> sections) {
        if (sections.size() > MAX_SECTIONS) return null;
        final ByteBuffer b = ByteBuffer.allocate(CHANNEL_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, CHANNEL_MAGIC);
        b.putShort(4, (short) CHANNEL_VERSION);
        b.putShort(6, (short) CHANNEL_SIZE);
        b.put(12, (byte) sections.size());
        int offset = DATA_OFFSET;
        for (int i = 0; i < sections.size(); i++) {
            final byte[] bytes = sections.valueAt(i);
            if (offset + bytes.length > CHANNEL_SIZE) return null;
            final int entry = TABLE_OFFSET + 8 * i;
            b.put(entry, (byte) sections.keyAt(i));
            b.putShort(entry + 2, (short) offset);
            b.putShort(entry + 4, (short) bytes.length);
            b.put(offset, bytes);
            offset += bytes.length;
        }
        return b.array();
    }

    /** Drops pages of processes that are gone. Holds mLock. */
    private static void prunePages(Channel channel) {
        final Iterator<Page> it = channel.pages.iterator();
        while (it.hasNext()) {
            final Page page = it.next();
            if (Process.getUidForPid(page.pid) != page.uid) {
                it.remove();
                page.close();
            }
        }
    }

    /** What the channel's sections arm, one marker per user the package is installed for. */
    private void writeMarkers(Channel channel) {
        boolean armed = false;
        boolean video = false;
        for (int i = 0; i < channel.sections.size(); i++) {
            final int id = channel.sections.keyAt(i);
            if (id >= FIRST_VIDEO_SECTION && id <= LAST_VIDEO_SECTION) {
                video = true;
            } else {
                armed = true;
            }
        }
        final PackageManager pm = mContext.getPackageManager();
        for (UserHandle user : mContext.getSystemService(UserManager.class).getUserHandles(true)) {
            final int uid;
            try {
                uid = pm.getPackageUidAsUser(channel.packageName, user.getIdentifier());
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            writeMarker(new File(MARKER_DIR, Integer.toString(uid)), armed);
            writeMarker(new File(VIDEO_MARKER_DIR, Integer.toString(uid)), video);
        }
    }

    private static void writeMarker(File marker, boolean present) {
        if (!present) {
            marker.delete();
            return;
        }
        try {
            marker.createNewFile();
        } catch (IOException e) {
            Slog.w(TAG, "marker " + marker + ": " + e);
        }
    }

    private void forget(String pkg) {
        synchronized (mLock) {
            final Channel channel = mChannels.get(pkg);
            if (channel == null) return;
            channel.sections.clear();
            commit(channel);
        }
    }

    /** Persisted as repeated (u8 id, u16 length, bytes). Holds mLock. */
    private void persist(Channel channel) {
        final AtomicFile file = new AtomicFile(new File(mStateDir, channel.packageName));
        if (channel.sections.size() == 0) {
            file.delete();
            return;
        }
        mStateDir.mkdirs();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < channel.sections.size(); i++) {
            final byte[] bytes = channel.sections.valueAt(i);
            out.write(channel.sections.keyAt(i));
            out.write(bytes.length & 0xff);
            out.write(bytes.length >> 8);
            out.write(bytes, 0, bytes.length);
        }
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(out.toByteArray());
            file.finishWrite(stream);
        } catch (IOException e) {
            file.failWrite(stream);
            Slog.w(TAG, "persist " + channel.packageName + ": " + e);
        }
    }

    /** At boot: the persisted packages, and markers only for them. */
    private void load() {
        synchronized (mLock) {
            if (!makeMarkerDir()) return;
            for (File dir : new File[] {MARKER_DIR, VIDEO_MARKER_DIR}) {
                final File[] stale = dir.listFiles();
                if (stale == null) continue;
                for (File marker : stale) marker.delete();
            }
            final File[] files = mStateDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                final String pkg = f.getName();
                if (pkg.endsWith(".new") || pkg.endsWith(".bak")) continue;
                final SparseArray<byte[]> sections = parse(new AtomicFile(f));
                if (sections == null || sections.size() == 0 || image(sections) == null) {
                    new AtomicFile(f).delete();
                    continue;
                }
                final Channel channel = new Channel(pkg);
                for (int i = 0; i < sections.size(); i++) {
                    channel.sections.put(sections.keyAt(i), sections.valueAt(i));
                }
                mChannels.put(pkg, channel);
                if (commit(channel) != OK) mChannels.remove(pkg);
            }
            Slog.i(TAG, mChannels.size() + " packages armed");
        }
    }

    /** The marker directories, searchable by every app; sepolicy labels them on creation. */
    private static boolean makeMarkerDir() {
        for (File dir : new File[] {MARKER_DIR.getParentFile(), MARKER_DIR, VIDEO_MARKER_DIR}) {
            try {
                if (!dir.isDirectory()) Os.mkdir(dir.getPath(), 0711);
                Os.chmod(dir.getPath(), 0711);
            } catch (ErrnoException e) {
                Slog.e(TAG, "cannot make " + dir + ": " + e + "; nothing is armed");
                return false;
            }
        }
        return true;
    }

    @Nullable
    static SparseArray<byte[]> parse(AtomicFile file) {
        final byte[] raw;
        try {
            raw = file.readFully();
        } catch (IOException e) {
            return null;
        }
        final SparseArray<byte[]> sections = new SparseArray<>();
        int i = 0;
        while (i + 3 <= raw.length) {
            final int id = raw[i] & 0xff;
            final int length = (raw[i + 1] & 0xff) | (raw[i + 2] & 0xff) << 8;
            i += 3;
            if (id < 1 || id > MAX_SECTION_ID || length == 0 || i + length > raw.length) {
                return null;
            }
            final byte[] bytes = new byte[length];
            System.arraycopy(raw, i, bytes, 0, length);
            sections.put(id, bytes);
            i += length;
        }
        return i == raw.length ? sections : null;
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        synchronized (mLock) {
            pw.println("framefx broker: " + mChannels.size() + " armed packages");
            for (int c = 0; c < mChannels.size(); c++) {
                final Channel channel = mChannels.valueAt(c);
                prunePages(channel);
                pw.println("  " + channel.packageName);
                for (int i = 0; i < channel.sections.size(); i++) {
                    pw.println("    section " + channel.sections.keyAt(i) + ": "
                            + hex(channel.sections.valueAt(i)));
                }
                for (Page page : channel.pages) {
                    final byte[] bytes = new byte[STATUS_SIZE];
                    if (!nativeSnapshot(page.map, bytes)) {
                        pw.println("    pid " + page.pid + " busy");
                        continue;
                    }
                    pw.println("    pid " + page.pid + " luwes " + luwes(bytes));
                    pw.println("    pid " + page.pid + " kelir " + kelir(bytes));
                    pw.println("    pid " + page.pid + " video " + video(bytes));
                }
            }
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** The Kelir status block, readable. */
    private static String kelir(byte[] page) {
        final ByteBuffer b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        final int at = 64;
        return String.format("state=%d reason=%d api=%d format=%d %dx%d turns=%d path=%d"
                        + " reject=%d applied=%d rejected=%d gpu_us=%.1f signature=%016x",
                b.get(at), b.get(at + 1), b.get(at + 2), b.get(at + 3),
                b.getShort(at + 4) & 0xffff, b.getShort(at + 6) & 0xffff, b.get(at + 8),
                b.get(at + 9), b.get(at + 10), b.getInt(at + 12) & 0xffffffffL,
                b.getInt(at + 16) & 0xffffffffL, (b.getInt(at + 20) & 0xffffffffL) / 1000.0,
                b.getLong(at + 24));
    }

    /** The Video status block, readable. */
    private static String video(byte[] page) {
        final ByteBuffer b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        final int at = 304;
        return String.format("state=%d reason=%d source=%dx%d out=%dx%d fps=%.1f presented=%.1f"
                        + " gpu_us=%.1f frames=%d generated=%d dropped=%d delay_ms=%.1f upscaled=%d"
                        + " applied=%d",
                b.get(at), b.get(at + 1), b.getShort(at + 4) & 0xffff, b.getShort(at + 6) & 0xffff,
                b.getShort(at + 8) & 0xffff, b.getShort(at + 10) & 0xffff, b.getFloat(at + 12),
                b.getFloat(at + 32), (b.getInt(at + 16) & 0xffffffffL) / 1000.0,
                b.getInt(at + 20) & 0xffffffffL, b.getInt(at + 36) & 0xffffffffL,
                b.getInt(at + 24) & 0xffffffffL, (b.getShort(at + 40) & 0xffff) / 10.0, b.get(at + 42),
                b.getInt(at + 28) & 0xffffffffL);
    }

    /** The Luwes status block, readable. */
    private static String luwes(byte[] page) {
        final ByteBuffer b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
        final int at = 16;
        return String.format("state=%d reason=%d api=%d source=%d %dx%d mult=%d gen=%d mode=%d"
                        + " paced=%d real=%.1f presented=%.1f panel=%.1f generated=%d",
                b.get(at), b.get(at + 1), b.get(at + 2), b.get(at + 3),
                b.getShort(at + 4) & 0xffff, b.getShort(at + 6) & 0xffff, b.get(at + 8),
                b.get(at + 9), b.get(at + 10), b.get(at + 11), b.getFloat(at + 12),
                b.getFloat(at + 16), b.getFloat(at + 20), b.getInt(at + 24) & 0xffffffffL);
    }
}
