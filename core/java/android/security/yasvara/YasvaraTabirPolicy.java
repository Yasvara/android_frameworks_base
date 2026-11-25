/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import java.util.Arrays;

/**
 * Lock-free immutable snapshot of Unified Framework Tabir rules.
 *
 * <p>Lives in {@code framework.jar} so both {@code services.jar} ({@code AppsFilterBase},
 * {@code PermissionService}, {@code ComputerEngine}, {@code BroadcastController}) and in-process
 * {@code SettingsProvider} read the exact same {@code volatile} reference with zero IPC, zero
 * reflection, and sub-nanosecond empty fast-path cost.
 *
 * @hide
 */
public final class YasvaraTabirPolicy {
    public static final int FIRST_APPLICATION_UID = 10000;
    public static final int LAST_APPLICATION_UID = 19999;

    public static final int SCOPE_SELECTED = 0;
    public static final int SCOPE_ALL_USER_APPS = 1;

    public static final int MODE_GLOBAL = 0;
    public static final int MODE_BLACKLIST = 1;
    public static final int MODE_WHITELIST = 2;

    public static final int DEV_HIDE_OFF = 0;
    public static final int DEV_HIDE_OBSERVERS = 1;
    public static final int DEV_HIDE_ALL_NON_SYSTEM = 2;

    private static final int[] NO_INTS = new int[0];
    private static final int[][] NO_INT_MATRIX = new int[0][];
    private static final String[] NO_STRINGS = new String[0];
    private static final String[][] NO_STRING_MATRIX = new String[0][];

    public static final YasvaraTabirPolicy EMPTY = new Builder().build();

    private static volatile YasvaraTabirPolicy sCurrent = EMPTY;

    private final boolean mIsEmpty;
    private final int[] mGlobalHiddenAppIds;
    private final String[] mGlobalHiddenRomPackages;
    private final boolean mGlobalDenyByDefault;
    /** Global observers when !mGlobalDenyByDefault; allowed callers when mGlobalDenyByDefault. */
    private final int[] mGlobalCallers;

    /** System apps + default Home launcher: never filtered as callers under any mode. */
    private final int[] mExemptCallers;
    /** Non-ROM system appIds (>= 10000): never hidden as targets even in Whitelist mode. */
    private final int[] mProtectedSystemAppIds;
    /** Installed hideable ROM signature packages, hidden in Whitelist mode unless allowed. */
    private final String[] mKnownHideableRomPackages;

    /** Per-observer overrides sorted by observerAppId. */
    private final int[] mOverrideCallers;
    private final int[] mOverrideModes;
    private final int[][] mOverrideAppIds;
    private final String[][] mOverrideRomPackages;

    private final int mDevHideMode;

    private YasvaraTabirPolicy(Builder b) {
        mGlobalHiddenAppIds = sortedUnique(b.mGlobalHiddenAppIds);
        mGlobalHiddenRomPackages = sortedUnique(b.mGlobalHiddenRomPackages);
        mGlobalDenyByDefault = b.mGlobalDenyByDefault;
        mGlobalCallers = sortedUnique(b.mGlobalCallers);
        mExemptCallers = sortedUnique(b.mExemptCallers);
        mProtectedSystemAppIds = sortedUnique(b.mProtectedSystemAppIds);
        mKnownHideableRomPackages = sortedUnique(b.mKnownHideableRomPackages);

        int count = b.mOverrideCallers.length;
        if (count == 0) {
            mOverrideCallers = NO_INTS;
            mOverrideModes = NO_INTS;
            mOverrideAppIds = NO_INT_MATRIX;
            mOverrideRomPackages = NO_STRING_MATRIX;
        } else {
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            Arrays.sort(order, (x, y) -> Integer.compare(b.mOverrideCallers[x], b.mOverrideCallers[y]));

            mOverrideCallers = new int[count];
            mOverrideModes = new int[count];
            mOverrideAppIds = new int[count][];
            mOverrideRomPackages = new String[count][];
            for (int i = 0; i < count; i++) {
                int src = order[i];
                mOverrideCallers[i] = b.mOverrideCallers[src];
                mOverrideModes[i] = b.mOverrideModes[src];
                mOverrideAppIds[i] = sortedUnique(b.mOverrideAppIds[src]);
                mOverrideRomPackages[i] = sortedUnique(b.mOverrideRomPackages[src]);
            }
        }

        mDevHideMode = b.mDevHideMode;
        mIsEmpty = mGlobalHiddenAppIds.length == 0
                && mGlobalHiddenRomPackages.length == 0
                && mOverrideCallers.length == 0;
    }

    public static YasvaraTabirPolicy current() {
        return sCurrent;
    }

    public static void install(YasvaraTabirPolicy policy) {
        sCurrent = policy != null ? policy : EMPTY;
    }

    public static boolean shouldHidePackage(int callingAppId, int targetAppId, String targetPkg) {
        return sCurrent.hidesPackage(callingAppId, targetAppId, targetPkg);
    }

    public static boolean shouldHideUid(int callingAppId, int targetAppId) {
        return sCurrent.hidesUid(callingAppId, targetAppId);
    }

    public static boolean shouldConcealDevSettings(int callingAppId) {
        return sCurrent.concealsDevSettings(callingAppId);
    }

    public static boolean isDevConcealedGlobalKey(String name) {
        if (name == null || name.isEmpty()) return false;
        char c = name.charAt(0);
        if (c != 'a' && c != 'd') return false;
        return "adb_enabled".equals(name)
                || "adb_wifi_enabled".equals(name)
                || "development_settings_enabled".equals(name);
    }

    public int globalHiddenCount() {
        return mGlobalHiddenAppIds.length + mGlobalHiddenRomPackages.length;
    }

    public boolean hidesUid(int callingAppId, int targetAppId) {
        if (mIsEmpty) return false;
        if (callingAppId < FIRST_APPLICATION_UID || callingAppId > LAST_APPLICATION_UID) {
            return false;
        }
        if (targetAppId < FIRST_APPLICATION_UID || callingAppId == targetAppId) {
            return false;
        }

        if (mOverrideCallers.length > 0) {
            int idx = Arrays.binarySearch(mOverrideCallers, callingAppId);
            if (idx >= 0) {
                if (contains(mExemptCallers, callingAppId)) return false;
                int mode = mOverrideModes[idx];
                if (mode == MODE_BLACKLIST) {
                    return contains(mOverrideAppIds[idx], targetAppId);
                }
                if (mode == MODE_WHITELIST) {
                    if (contains(mProtectedSystemAppIds, targetAppId)) return false;
                    return !contains(mOverrideAppIds[idx], targetAppId);
                }
            }
        }

        if (!contains(mGlobalHiddenAppIds, targetAppId)) return false;
        return isGlobalObserver(callingAppId);
    }

    public boolean hidesPackage(int callingAppId, int targetAppId, String targetPkg) {
        if (mIsEmpty) return false;
        if (callingAppId < FIRST_APPLICATION_UID || callingAppId > LAST_APPLICATION_UID) {
            return false;
        }
        if (callingAppId == targetAppId) {
            return false;
        }
        if ("android".equals(targetPkg)) {
            return false;
        }

        // Shared system UID targets (e.g. uid 1000 for org.lineageos.lineageparts) can ONLY be
        // hidden by exact package-name match so "android" and core system packages stay intact.
        if (targetAppId < FIRST_APPLICATION_UID) {
            if (targetPkg == null) return false;
            if (mOverrideCallers.length > 0) {
                int idx = Arrays.binarySearch(mOverrideCallers, callingAppId);
                if (idx >= 0) {
                    if (contains(mExemptCallers, callingAppId)) return false;
                    int mode = mOverrideModes[idx];
                    if (mode == MODE_BLACKLIST) {
                        return contains(mOverrideRomPackages[idx], targetPkg);
                    }
                    if (mode == MODE_WHITELIST) {
                        return contains(mKnownHideableRomPackages, targetPkg)
                                && !contains(mOverrideRomPackages[idx], targetPkg);
                    }
                }
            }
            if (!contains(mGlobalHiddenRomPackages, targetPkg)) return false;
            return isGlobalObserver(callingAppId);
        }

        // Application UID target (>= 10000).
        if (mOverrideCallers.length > 0) {
            int idx = Arrays.binarySearch(mOverrideCallers, callingAppId);
            if (idx >= 0) {
                if (contains(mExemptCallers, callingAppId)) return false;
                int mode = mOverrideModes[idx];
                if (mode == MODE_BLACKLIST) {
                    return contains(mOverrideAppIds[idx], targetAppId)
                            || (targetPkg != null && contains(mOverrideRomPackages[idx], targetPkg));
                }
                if (mode == MODE_WHITELIST) {
                    if (contains(mProtectedSystemAppIds, targetAppId)) {
                        return targetPkg != null
                                && contains(mKnownHideableRomPackages, targetPkg)
                                && !contains(mOverrideRomPackages[idx], targetPkg);
                    }
                    if (targetPkg != null && contains(mOverrideRomPackages[idx], targetPkg)) {
                        return false;
                    }
                    return !contains(mOverrideAppIds[idx], targetAppId);
                }
            }
        }

        boolean globallyHidden = contains(mGlobalHiddenAppIds, targetAppId)
                || (targetPkg != null && contains(mGlobalHiddenRomPackages, targetPkg));
        if (!globallyHidden) return false;
        return isGlobalObserver(callingAppId);
    }

    public boolean concealsDevSettings(int callingAppId) {
        if (mDevHideMode == DEV_HIDE_OFF) return false;
        if (callingAppId < FIRST_APPLICATION_UID || callingAppId > LAST_APPLICATION_UID) {
            return false;
        }
        if (contains(mExemptCallers, callingAppId)) return false;
        if (mDevHideMode == DEV_HIDE_ALL_NON_SYSTEM) return true;
        if (mDevHideMode == DEV_HIDE_OBSERVERS) {
            if (contains(mOverrideCallers, callingAppId)) return true;
            return isGlobalObserver(callingAppId);
        }
        return false;
    }

    private boolean isGlobalObserver(int callingAppId) {
        if (contains(mExemptCallers, callingAppId)) return false;
        return contains(mGlobalCallers, callingAppId) != mGlobalDenyByDefault;
    }

    private static boolean contains(int[] sorted, int key) {
        return sorted.length > 0 && Arrays.binarySearch(sorted, key) >= 0;
    }

    private static boolean contains(String[] sorted, String key) {
        return sorted.length > 0 && Arrays.binarySearch(sorted, key) >= 0;
    }

    private static int[] sortedUnique(int[] values) {
        if (values == null || values.length == 0) return NO_INTS;
        int[] copy = values.clone();
        Arrays.sort(copy);
        int unique = 1;
        for (int i = 1; i < copy.length; i++) {
            if (copy[i] != copy[unique - 1]) {
                copy[unique++] = copy[i];
            }
        }
        return unique == copy.length ? copy : Arrays.copyOf(copy, unique);
    }

    private static String[] sortedUnique(String[] values) {
        if (values == null || values.length == 0) return NO_STRINGS;
        int nonNull = 0;
        for (String s : values) {
            if (s != null && !s.isEmpty()) nonNull++;
        }
        if (nonNull == 0) return NO_STRINGS;
        String[] copy = new String[nonNull];
        int pos = 0;
        for (String s : values) {
            if (s != null && !s.isEmpty()) copy[pos++] = s;
        }
        Arrays.sort(copy);
        int unique = 1;
        for (int i = 1; i < copy.length; i++) {
            if (!copy[i].equals(copy[unique - 1])) {
                copy[unique++] = copy[i];
            }
        }
        return unique == copy.length ? copy : Arrays.copyOf(copy, unique);
    }

    public static final class Builder {
        private int[] mGlobalHiddenAppIds = NO_INTS;
        private String[] mGlobalHiddenRomPackages = NO_STRINGS;
        private boolean mGlobalDenyByDefault;
        private int[] mGlobalCallers = NO_INTS;
        private int[] mExemptCallers = NO_INTS;
        private int[] mProtectedSystemAppIds = NO_INTS;
        private String[] mKnownHideableRomPackages = NO_STRINGS;
        private int[] mOverrideCallers = NO_INTS;
        private int[] mOverrideModes = NO_INTS;
        private int[][] mOverrideAppIds = NO_INT_MATRIX;
        private String[][] mOverrideRomPackages = NO_STRING_MATRIX;
        private int mDevHideMode = DEV_HIDE_OFF;

        public Builder setGlobalSelected(int[] hiddenAppIds, String[] hiddenRomPackages,
                int[] observers) {
            mGlobalHiddenAppIds = hiddenAppIds != null ? hiddenAppIds : NO_INTS;
            mGlobalHiddenRomPackages = hiddenRomPackages != null ? hiddenRomPackages : NO_STRINGS;
            mGlobalDenyByDefault = false;
            mGlobalCallers = observers != null ? observers : NO_INTS;
            return this;
        }

        public Builder setGlobalAllExcept(int[] hiddenAppIds, String[] hiddenRomPackages,
                int[] allowedCallers) {
            mGlobalHiddenAppIds = hiddenAppIds != null ? hiddenAppIds : NO_INTS;
            mGlobalHiddenRomPackages = hiddenRomPackages != null ? hiddenRomPackages : NO_STRINGS;
            mGlobalDenyByDefault = true;
            mGlobalCallers = allowedCallers != null ? allowedCallers : NO_INTS;
            return this;
        }

        public Builder setExemptCallers(int[] exemptCallers) {
            mExemptCallers = exemptCallers != null ? exemptCallers : NO_INTS;
            return this;
        }

        public Builder setProtectedSystemAppIds(int[] protectedSystemAppIds) {
            mProtectedSystemAppIds = protectedSystemAppIds != null ? protectedSystemAppIds : NO_INTS;
            return this;
        }

        public Builder setKnownHideableRomPackages(String[] knownHideableRomPackages) {
            mKnownHideableRomPackages =
                    knownHideableRomPackages != null ? knownHideableRomPackages : NO_STRINGS;
            return this;
        }

        public Builder setObserverOverrides(int[] callers, int[] modes, int[][] appIds,
                String[][] romPackages) {
            if (callers == null || callers.length == 0) {
                mOverrideCallers = NO_INTS;
                mOverrideModes = NO_INTS;
                mOverrideAppIds = NO_INT_MATRIX;
                mOverrideRomPackages = NO_STRING_MATRIX;
                return this;
            }
            if (modes == null || appIds == null || romPackages == null
                    || modes.length != callers.length
                    || appIds.length != callers.length
                    || romPackages.length != callers.length) {
                throw new IllegalArgumentException("Mismatched override array lengths");
            }
            mOverrideCallers = callers;
            mOverrideModes = modes;
            mOverrideAppIds = appIds;
            mOverrideRomPackages = romPackages;
            return this;
        }

        public Builder setDevHideMode(int devHideMode) {
            mDevHideMode = devHideMode;
            return this;
        }

        public YasvaraTabirPolicy build() {
            return new YasvaraTabirPolicy(this);
        }
    }
}
