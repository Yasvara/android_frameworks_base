/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.visibility;

import android.security.yasvara.YasvaraTabirPolicy;

/**
 * Service-layer facade delegating lock-free visibility lookups to {@link YasvaraTabirPolicy}.
 *
 * <p>Preserves full backwards compatibility with M1/M2 callers while supporting package-name
 * matching for shared-system-UID ROM packages, per-observer Blacklist/Whitelist modes, reusable
 * templates, UID side-channel filtering, and Developer Options / ADB concealment.
 */
public final class AppVisibilityPolicy {
    private static final AppVisibilityPolicy EMPTY =
            new AppVisibilityPolicy(YasvaraTabirPolicy.EMPTY);

    private static volatile AppVisibilityPolicy sCurrent = EMPTY;

    private final YasvaraTabirPolicy mTabir;

    public AppVisibilityPolicy(YasvaraTabirPolicy tabir) {
        mTabir = tabir != null ? tabir : YasvaraTabirPolicy.EMPTY;
    }

    /** Hides [hidden] from exactly [observers]. */
    public static AppVisibilityPolicy selected(int[] hidden, int[] observers) {
        return new AppVisibilityPolicy(
                new YasvaraTabirPolicy.Builder()
                        .setGlobalSelected(hidden, null, observers)
                        .build());
    }

    /** Hides [hidden] from everyone but [allowed]. */
    public static AppVisibilityPolicy allExcept(int[] hidden, int[] allowed) {
        return new AppVisibilityPolicy(
                new YasvaraTabirPolicy.Builder()
                        .setGlobalAllExcept(hidden, null, allowed)
                        .build());
    }

    public static void install(AppVisibilityPolicy policy) {
        AppVisibilityPolicy next = policy != null ? policy : EMPTY;
        sCurrent = next;
        YasvaraTabirPolicy.install(next.mTabir);
    }

    public static void install(YasvaraTabirPolicy tabir) {
        install(new AppVisibilityPolicy(tabir));
    }

    public static boolean shouldHide(int callingAppId, int targetAppId) {
        return YasvaraTabirPolicy.shouldHideUid(callingAppId, targetAppId);
    }

    public static boolean shouldHide(int callingAppId, int targetAppId, String targetPkg) {
        return YasvaraTabirPolicy.shouldHidePackage(callingAppId, targetAppId, targetPkg);
    }

    public static boolean shouldConcealDevSettings(int callingAppId) {
        return YasvaraTabirPolicy.shouldConcealDevSettings(callingAppId);
    }

    public boolean hides(int callingAppId, int targetAppId) {
        return mTabir.hidesUid(callingAppId, targetAppId);
    }

    public boolean hides(int callingAppId, int targetAppId, String targetPkg) {
        return mTabir.hidesPackage(callingAppId, targetAppId, targetPkg);
    }

    public boolean concealsDevSettings(int callingAppId) {
        return mTabir.concealsDevSettings(callingAppId);
    }

    public int hiddenCount() {
        return mTabir.globalHiddenCount();
    }

    public YasvaraTabirPolicy tabir() {
        return mTabir;
    }
}
