/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.android.server.yasvara.kait;

import android.content.Context;
import android.os.Parcel;
import android.util.Slog;

/**
 * The sole entrypoint the rest of {@code system_server} sees of Kait.
 *
 * <p>Three tiny seams call in: {@code SystemServer} starts it, {@code ProcessList} asks
 * {@link #shouldInject} while assembling Zygote flags, and {@code ActivityManagerService} offers
 * {@link #onRendezvous} / {@link #onManage} for reserved stealth transaction codes.
 */
public final class YasvaraKait {
    private static final String TAG = "YasvaraKait";

    /** Transaction code on {@code activity} used by scoped processes to claim their service. */
    public static final int RENDEZVOUS_CODE = ('_' << 24) | ('Y' << 16) | ('K' << 8) | 'P';

    /**
     * Transaction code on {@code activity} returning {@code IYasvaraKait} to system, shell, and
     * holders of {@code yasvara.permission.MANAGE_KAIT}. Never registered in {@code servicemanager}.
     */
    public static final int MANAGE_CODE = ('_' << 24) | ('Y' << 16) | ('K' << 8) | 'M';

    private static volatile KaitManagerService sService;

    private YasvaraKait() {}

    /** Starts the Kait manager service. Never throws; on failure injection stays off. */
    public static void start(Context context) {
        try {
            sService = new KaitManagerService(context);
        } catch (Throwable t) {
            Slog.e(TAG, "Kait runtime failed to start; injection stays off", t);
        }
    }

    /** True when a process of this app must be started with {@code Zygote.YASVARA_KAIT}. */
    public static boolean shouldInject(String packageName, int uid, boolean isolated) {
        final KaitManagerService service = sService;
        return service != null && service.shouldInject(packageName, uid, isolated);
    }

    /** Answers a rendezvous transaction, or false so unscoped processes see stock Android. */
    public static boolean onRendezvous(int uid, int pid, String packageName, String processName,
            boolean isolated, Parcel data, Parcel reply) {
        final KaitManagerService service = sService;
        return service != null
                && service.onRendezvous(uid, pid, packageName, processName, isolated, data, reply);
    }

    /** Answers a management transaction for authorised callers only. */
    public static boolean onManage(int uid, int pid, Parcel reply) {
        final KaitManagerService service = sService;
        if (service == null || !service.mayManage(uid, pid)) return false;
        reply.writeNoException();
        reply.writeStrongBinder(service);
        return true;
    }

    /** {@code cmd activity kait ...} (and legacy alias {@code cmd activity xposed ...}). */
    public static int runShell(android.os.ShellCommand sc, int uid, int pid) {
        final KaitManagerService service = sService;
        if (service == null) {
            sc.getErrPrintWriter().println("Kait runtime is not running");
            return 1;
        }
        if (!service.mayManage(uid, pid)) {
            sc.getErrPrintWriter().println("Error: not allowed");
            return 1;
        }
        return service.runShell(sc);
    }
}
