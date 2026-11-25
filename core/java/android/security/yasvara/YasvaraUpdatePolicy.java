/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Constants and binder lookup for the per-app update policy. The policy is kept
 * and enforced by {@code YasvaraUpdatePolicyService} in system_server; Laras and Settings
 * edit it through {@link IYasvaraUpdatePolicy}.
 *
 * @hide
 */
public final class YasvaraUpdatePolicy {
    public static final String SERVICE_NAME = "yasvara_update_policy";

    /** Stores update the app as usual. */
    public static final int POLICY_AUTO = 0;
    /** A version is installable only after it has been offered for the rule's number of days. */
    public static final int POLICY_DELAY = 1;
    /** Every store update needs the owner's confirmation. */
    public static final int POLICY_ASK = 2;
    /**
     * No store may update the app ("paused"). By default the app is also handed to Laras as its
     * update owner, so stores that honour update ownership (Play) stop offering updates at all.
     */
    public static final int POLICY_NEVER = 3;
    /** One versionCode is refused; any later one installs and the rule goes back to Auto. */
    public static final int POLICY_SKIP = 4;

    /** How long an "Allow once" grant waits for the store to deliver the update. */
    public static final long ALLOW_ONCE_WINDOW_MS = 24L * 60 * 60 * 1000;

    public static final int MIN_DELAY_DAYS = 1;
    public static final int MAX_DELAY_DAYS = 365;
    public static final int DEFAULT_DELAY_DAYS = 7;

    /**
     * Explicit broadcast to Laras for each blocked or to-be-confirmed store update, so it can
     * notify the owner. Extras: {@link #EXTRA_PACKAGE}, {@link #EXTRA_VERSION_CODE},
     * {@link #EXTRA_INSTALLER}, {@link #EXTRA_DECISION}; sent with MANAGE_UPDATE_POLICY.
     */
    public static final String ACTION_UPDATE_EVENT = "org.yasvara.laras.action.UPDATE_EVENT";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_VERSION_CODE = "version_code";
    public static final String EXTRA_INSTALLER = "installer";
    /**
     * "blocked", "skipped" or "ask" (a store update); "reverted" (a detached core app crash-looped, §4.8);
     * "rollback_crash" (an app crashed soon after a rollback, §4.4); "stale" (paused for
     * {@link #STALE_REMINDER_DAYS} days, §9).
     */
    public static final String EXTRA_DECISION = "decision";
    public static final int STALE_REMINDER_DAYS = 90;
    public static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_UPDATE_POLICY";
    public static final String LARAS_PACKAGE = "org.yasvara.laras";

    private YasvaraUpdatePolicy() {}

    /** True for a known POLICY_* value. */
    public static boolean isValid(int policy) {
        return policy >= POLICY_AUTO && policy <= POLICY_SKIP;
    }

    /** Clamps a delay to the supported range. */
    public static int clampDelay(int days) {
        return Math.max(MIN_DELAY_DAYS, Math.min(MAX_DELAY_DAYS, days));
    }

    /** Binder handle, or null when the service is not running. Never throws. */
    public static IYasvaraUpdatePolicy service() {
        try {
            IBinder b = ServiceManager.getService(SERVICE_NAME);
            return b == null ? null : IYasvaraUpdatePolicy.Stub.asInterface(b);
        } catch (Throwable t) {
            return null;
        }
    }
}
