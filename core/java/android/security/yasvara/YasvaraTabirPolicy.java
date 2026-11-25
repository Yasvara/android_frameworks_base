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
 * reflection, and O(1) cache-line bitset lookups on the hot path.
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

    private static final int BITSET_MAX_SPAN = 16384;
    private static final int[] NO_INTS = new int[0];
    private static final int[][] NO_INT_MATRIX = new int[0][];
    private static final String[] NO_STRINGS = new String[0];
    private static final String[][] NO_STRING_MATRIX = new String[0][];
    private static final IntSet[] NO_INT_SETS = new IntSet[0];
    private static final StringSet[] NO_STRING_SETS = new StringSet[0];

    public static final YasvaraTabirPolicy EMPTY = new Builder().build();

    private static volatile YasvaraTabirPolicy sCurrent = EMPTY;

    /** Range-trimmed O(1) bitset for Android appIds, falling back to binary search for wide ranges. */
    private static final class IntSet {
        static final IntSet EMPTY = new IntSet(NO_INTS);

        final int min;
        final int max;
        final long[] bits;
        final int[] sorted;

        IntSet(int[] raw) {
            int[] arr = sortedUnique(raw);
            sorted = arr;
            if (arr.length == 0) {
                min = Integer.MAX_VALUE;
                max = Integer.MIN_VALUE;
                bits = null;
                return;
            }
            min = arr[0];
            max = arr[arr.length - 1];
            long span = (long) max - (long) min;
            if (span >= 0 && span <= BITSET_MAX_SPAN) {
                long[] b = new long[(int) (span >>> 6) + 1];
                for (int v : arr) {
                    int off = v - min;
                    b[off >>> 6] |= 1L << (off & 63);
                }
                bits = b;
            } else {
                bits = null;
            }
        }

        boolean isEmpty() {
            return sorted.length == 0;
        }

        int size() {
            return sorted.length;
        }

        boolean contains(int key) {
            if (key < min || key > max) return false;
            if (bits != null) {
                int off = key - min;
                return (bits[off >>> 6] & (1L << (off & 63))) != 0L;
            }
            return Arrays.binarySearch(sorted, key) >= 0;
        }
    }

    /** Length-bounded + first-char bitmask pre-filtered sorted String set (0 comparisons on miss). */
    private static final class StringSet {
        static final StringSet EMPTY = new StringSet(NO_STRINGS);

        final String[] sorted;
        final int minLen;
        final int maxLen;
        final long firstCharMask;

        StringSet(String[] raw) {
            String[] arr = sortedUnique(raw);
            sorted = arr;
            if (arr.length == 0) {
                minLen = Integer.MAX_VALUE;
                maxLen = -1;
                firstCharMask = 0L;
                return;
            }
            int lo = Integer.MAX_VALUE;
            int hi = 0;
            long mask = 0L;
            for (String s : arr) {
                int len = s.length();
                if (len < lo) lo = len;
                if (len > hi) hi = len;
                mask |= 1L << (s.charAt(0) & 63);
            }
            minLen = lo;
            maxLen = hi;
            firstCharMask = mask;
        }

        boolean isEmpty() {
            return sorted.length == 0;
        }

        int size() {
            return sorted.length;
        }

        boolean contains(String key) {
            if (sorted.length == 0 || key == null) return false;
            int len = key.length();
            if (len < minLen || len > maxLen) return false;
            if ((firstCharMask & (1L << (key.charAt(0) & 63))) == 0L) return false;
            return Arrays.binarySearch(sorted, key) >= 0;
        }
    }

    private final boolean mIsEmpty;
    private final IntSet mGlobalHiddenAppIds;
    private final StringSet mGlobalHiddenRomPackages;
    private final boolean mGlobalDenyByDefault;
    /** Global observers when !mGlobalDenyByDefault; allowed callers when mGlobalDenyByDefault. */
    private final IntSet mGlobalCallers;

    /** System apps + default Home launcher: never filtered as callers under any mode. */
    private final IntSet mExemptCallers;
    /** Non-ROM system appIds (>= 10000): never hidden as targets even in Whitelist mode. */
    private final IntSet mProtectedSystemAppIds;
    /** Installed hideable ROM signature packages, hidden in Whitelist mode unless allowed. */
    private final StringSet mKnownHideableRomPackages;
    /** Installed Xposed module appIds when hideXposed is active (gates metadata stripping). */
    private final IntSet mXposedModuleAppIds;

    /** Per-observer overrides sorted by observerAppId (exempt callers pre-stripped at build time). */
    private final IntSet mOverrideCallerSet;
    private final int[] mOverrideCallers;
    private final int[] mOverrideModes;
    private final IntSet[] mOverrideAppIds;
    private final StringSet[] mOverrideRomPackages;

    private final int mDevHideMode;

    private YasvaraTabirPolicy(Builder b) {
        mGlobalHiddenAppIds = new IntSet(b.mGlobalHiddenAppIds);
        mGlobalHiddenRomPackages = new StringSet(b.mGlobalHiddenRomPackages);
        mGlobalDenyByDefault = b.mGlobalDenyByDefault;
        mGlobalCallers = new IntSet(b.mGlobalCallers);
        mExemptCallers = new IntSet(b.mExemptCallers);
        mProtectedSystemAppIds = new IntSet(b.mProtectedSystemAppIds);
        mKnownHideableRomPackages = new StringSet(b.mKnownHideableRomPackages);
        mXposedModuleAppIds = new IntSet(b.mXposedModuleAppIds);

        int rawCount = b.mOverrideCallers.length;
        int validCount = 0;
        for (int i = 0; i < rawCount; i++) {
            if (!mExemptCallers.contains(b.mOverrideCallers[i])) {
                validCount++;
            }
        }
        if (validCount == 0) {
            mOverrideCallerSet = IntSet.EMPTY;
            mOverrideCallers = NO_INTS;
            mOverrideModes = NO_INTS;
            mOverrideAppIds = NO_INT_SETS;
            mOverrideRomPackages = NO_STRING_SETS;
        } else {
            Integer[] order = new Integer[validCount];
            int pos = 0;
            for (int i = 0; i < rawCount; i++) {
                if (!mExemptCallers.contains(b.mOverrideCallers[i])) {
                    order[pos++] = i;
                }
            }
            Arrays.sort(order, (x, y) -> Integer.compare(b.mOverrideCallers[x], b.mOverrideCallers[y]));

            mOverrideCallers = new int[validCount];
            mOverrideModes = new int[validCount];
            mOverrideAppIds = new IntSet[validCount];
            mOverrideRomPackages = new StringSet[validCount];
            for (int i = 0; i < validCount; i++) {
                int src = order[i];
                mOverrideCallers[i] = b.mOverrideCallers[src];
                mOverrideModes[i] = b.mOverrideModes[src];
                mOverrideAppIds[i] = new IntSet(b.mOverrideAppIds[src]);
                mOverrideRomPackages[i] = new StringSet(b.mOverrideRomPackages[src]);
            }
            mOverrideCallerSet = new IntSet(mOverrideCallers);
        }

        mDevHideMode = b.mDevHideMode;
        mIsEmpty = mGlobalHiddenAppIds.isEmpty()
                && mGlobalHiddenRomPackages.isEmpty()
                && mOverrideCallers.length == 0;
    }

    public static YasvaraTabirPolicy current() {
        return sCurrent;
    }

    public static void install(YasvaraTabirPolicy policy) {
        sCurrent = policy != null ? policy : EMPTY;
    }

    public static boolean shouldHidePackage(int callingAppId, int targetAppId, String targetPkg) {
        final YasvaraTabirPolicy p = sCurrent;
        return !p.mIsEmpty && p.hidesPackage(callingAppId, targetAppId, targetPkg);
    }

    public static boolean shouldHideUid(int callingAppId, int targetAppId) {
        final YasvaraTabirPolicy p = sCurrent;
        return !p.mIsEmpty && p.hidesUid(callingAppId, targetAppId);
    }

    public static boolean shouldConcealDevSettings(int callingAppId) {
        final YasvaraTabirPolicy p = sCurrent;
        return p.mDevHideMode != DEV_HIDE_OFF && p.concealsDevSettings(callingAppId);
    }

    /**
     * Fast-path gate for {@code ComputerEngine.stripXposedMetaData}: avoids unparceling component
     * {@code metaData} Bundles unless {@code hideXposed} is active, the target is a known Xposed
     * module, and the caller is a non-exempt user app.
     */
    public static boolean shouldStripXposedMetaData(int callingAppId, int targetAppId) {
        final YasvaraTabirPolicy p = sCurrent;
        return !p.mXposedModuleAppIds.isEmpty() && p.stripsXposedMetaData(callingAppId, targetAppId);
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
        return mGlobalHiddenAppIds.size() + mGlobalHiddenRomPackages.size();
    }

    public boolean stripsXposedMetaData(int callingAppId, int targetAppId) {
        if (mXposedModuleAppIds.isEmpty()) return false;
        if (callingAppId < FIRST_APPLICATION_UID || callingAppId > LAST_APPLICATION_UID) {
            return false;
        }
        if (callingAppId == targetAppId || !mXposedModuleAppIds.contains(targetAppId)) {
            return false;
        }
        return !mExemptCallers.contains(callingAppId);
    }

    public boolean hidesUid(int callingAppId, int targetAppId) {
        if (mIsEmpty) return false;
        if (callingAppId < FIRST_APPLICATION_UID || callingAppId > LAST_APPLICATION_UID) {
            return false;
        }
        if (targetAppId < FIRST_APPLICATION_UID || callingAppId == targetAppId) {
            return false;
        }

        if (mOverrideCallerSet.contains(callingAppId)) {
            int idx = Arrays.binarySearch(mOverrideCallers, callingAppId);
            if (idx >= 0) {
                int mode = mOverrideModes[idx];
                if (mode == MODE_BLACKLIST) {
                    return mOverrideAppIds[idx].contains(targetAppId);
                }
                if (mode == MODE_WHITELIST) {
                    if (mProtectedSystemAppIds.contains(targetAppId)) return false;
                    return !mOverrideAppIds[idx].contains(targetAppId);
                }
            }
        }

        if (!mGlobalHiddenAppIds.contains(targetAppId)) return false;
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

        // Shared system UID targets (e.g. uid 1000 for org.lineageos.lineageparts) can ONLY be
        // hidden by exact package-name match so "android" and core system packages stay intact.
        if (targetAppId < FIRST_APPLICATION_UID) {
            if (targetPkg == null || "android".equals(targetPkg)) return false;
            if (mOverrideCallerSet.contains(callingAppId)) {
                int idx = Arrays.binarySearch(mOverrideCallers, callingAppId);
                if (idx >= 0) {
                    int mode = mOverrideModes[idx];
                    if (mode == MODE_BLACKLIST) {
                        return mOverrideRomPackages[idx].contains(targetPkg);
                    }
                    if (mode == MODE_WHITELIST) {
                        return mKnownHideableRomPackages.contains(targetPkg)
                                && !mOverrideRomPackages[idx].contains(targetPkg);
                    }
                }
            }
            if (!mGlobalHiddenRomPackages.contains(targetPkg)) return false;
            return isGlobalObserver(callingAppId);
        }

        // Application UID target (>= 10000).
        if (mOverrideCallerSet.contains(callingAppId)) {
            int idx = Arrays.binarySearch(mOverrideCallers, callingAppId);
            if (idx >= 0) {
                int mode = mOverrideModes[idx];
                if (mode == MODE_BLACKLIST) {
                    return mOverrideAppIds[idx].contains(targetAppId)
                            || mOverrideRomPackages[idx].contains(targetPkg);
                }
                if (mode == MODE_WHITELIST) {
                    if (mProtectedSystemAppIds.contains(targetAppId)) {
                        return mKnownHideableRomPackages.contains(targetPkg)
                                && !mOverrideRomPackages[idx].contains(targetPkg);
                    }
                    if (mOverrideRomPackages[idx].contains(targetPkg)) {
                        return false;
                    }
                    return !mOverrideAppIds[idx].contains(targetAppId);
                }
            }
        }

        if (!mGlobalHiddenAppIds.contains(targetAppId)
                && !mGlobalHiddenRomPackages.contains(targetPkg)) {
            return false;
        }
        return isGlobalObserver(callingAppId);
    }

    public boolean concealsDevSettings(int callingAppId) {
        if (mDevHideMode == DEV_HIDE_OFF) return false;
        if (callingAppId < FIRST_APPLICATION_UID || callingAppId > LAST_APPLICATION_UID) {
            return false;
        }
        if (mExemptCallers.contains(callingAppId)) return false;
        if (mDevHideMode == DEV_HIDE_ALL_NON_SYSTEM) return true;
        if (mDevHideMode == DEV_HIDE_OBSERVERS) {
            if (mOverrideCallerSet.contains(callingAppId)) return true;
            return mGlobalCallers.contains(callingAppId) != mGlobalDenyByDefault;
        }
        return false;
    }

    private boolean isGlobalObserver(int callingAppId) {
        if (mExemptCallers.contains(callingAppId)) return false;
        return mGlobalCallers.contains(callingAppId) != mGlobalDenyByDefault;
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
        private int[] mXposedModuleAppIds = NO_INTS;
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

        public Builder setXposedModuleAppIds(int[] xposedModuleAppIds) {
            mXposedModuleAppIds = xposedModuleAppIds != null ? xposedModuleAppIds : NO_INTS;
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
