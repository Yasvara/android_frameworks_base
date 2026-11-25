/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.lelap;

import android.app.ActivityManager;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What the gates in the power manager, process list, broadcast queue and job scheduler read.
 * A snapshot is immutable and swapped whole; with no rules every gate is one volatile load.
 */
public final class LelapPolicy {
    private static final LelapPolicy EMPTY =
            new LelapPolicy(new int[0], new ArraySet<>(), new ArraySet<>(), new String[0][]);
    private static final int MAX_EVENTS = 512;
    private static final long EVENT_REPEAT_MS = 10 * 60 * 1000;

    private static volatile LelapPolicy sCurrent = EMPTY;
    /** Strict packages the owner has not opened since they were put to sleep. */
    private static final Set<String> sAsleep = ConcurrentHashMap.newKeySet();

    /**
     * Sleeping packages with a widget on a home screen, and the components that draw it. These
     * are never stopped, so the widget stays live; one of its components may wake the process
     * for a short window.
     */
    private static final ConcurrentHashMap<String, Set<String>> sWidgets =
            new ConcurrentHashMap<>();
    /**
     * Manifest widget providers of strict packages. Bound widgets are readable only after the
     * first reconcile past unlock, but AppWidgetService updates every widget at unlock, so these
     * stand in until then.
     */
    private static final ConcurrentHashMap<String, Set<String>> sProviders =
            new ConcurrentHashMap<>();
    private static volatile boolean sWidgetsKnown;
    private static final ConcurrentHashMap<String, Long> sWindowEnd = new ConcurrentHashMap<>();
    static final long WIDGET_WINDOW_MS = 20 * 1000;
    private static volatile Consumer<String> sOnWidgetWindow;

    private static final Object sEventLock = new Object();
    @GuardedBy("sEventLock")
    private static final ArrayDeque<String> sEvents = new ArrayDeque<>();
    @GuardedBy("sEventLock")
    private static final ArrayMap<String, Long> sLastEvent = new ArrayMap<>();

    private final int[] mStrictAppIds;
    private final ArraySet<String> mStrict;
    private final ArraySet<String> mPush;
    /** Pairs of package ("*" for any) and a substring of the job's class or trace tag. */
    private final String[][] mJobs;

    LelapPolicy(int[] strictAppIds, ArraySet<String> strict, ArraySet<String> push,
            String[][] jobs) {
        mStrictAppIds = strictAppIds;
        Arrays.sort(mStrictAppIds);
        mStrict = strict;
        mPush = push;
        mJobs = jobs;
    }

    static void set(LelapPolicy policy) {
        sCurrent = policy;
        sAsleep.retainAll(policy.mStrict);
        sWidgets.keySet().retainAll(policy.mStrict);
        sProviders.keySet().retainAll(policy.mStrict);
    }

    static void setProviders(String pkg, Set<String> components) {
        if (components == null || components.isEmpty()) {
            sProviders.remove(pkg);
        } else {
            sProviders.put(pkg, components);
        }
    }

    static void setWidgetsKnown() {
        sWidgetsKnown = true;
    }

    /** Called with the package each time a widget window opens. */
    static void setWidgetWindowListener(Consumer<String> listener) {
        sOnWidgetWindow = listener;
    }

    /** Components in "{package/class}" form, or null when the package has no widget out. */
    static void setWidgetComponents(String pkg, Set<String> components) {
        if (components == null || components.isEmpty()) {
            sWidgets.remove(pkg);
        } else {
            sWidgets.put(pkg, components);
        }
    }

    static boolean hasWidget(String pkg) {
        return sWidgets.containsKey(pkg);
    }

    static boolean inWidgetWindow(String pkg) {
        final Long end = sWindowEnd.get(pkg);
        return end != null && SystemClock.uptimeMillis() < end;
    }

    static boolean isStrict(String pkg) {
        return sCurrent.mStrict.contains(pkg);
    }

    static boolean isAsleep(String pkg) {
        return sAsleep.contains(pkg);
    }

    static void sleep(String pkg) {
        sAsleep.add(pkg);
    }

    static void wake(String pkg) {
        sAsleep.remove(pkg);
        sWindowEnd.remove(pkg);
    }

    /** A strict app keeps a partial wakelock only while it is in front or runs a foreground service. */
    public static boolean wakeLockBlocked(int appId, int procState) {
        final int[] strict = sCurrent.mStrictAppIds;
        return strict.length != 0
                && procState != ActivityManager.PROCESS_STATE_NONEXISTENT
                && procState > ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                && Arrays.binarySearch(strict, appId) >= 0;
    }

    /**
     * Whether a process for a sleeping strict package must not start. An activity start is the
     * owner opening the app, which wakes it.
     */
    public static boolean startBlocked(String pkg, String hostingType, String hostingName) {
        if (sAsleep.isEmpty() || !sAsleep.contains(pkg)) return false;
        if (hostingType == null) return false;
        Set<String> widget = sWidgets.get(pkg);
        if (widget == null && !sWidgetsKnown) widget = sProviders.get(pkg);
        if (widget != null && !isActivity(hostingType)) {
            if (inWidgetWindow(pkg)) return false;
            if (hostingName != null && widget.contains(hostingName)) {
                sWindowEnd.put(pkg, SystemClock.uptimeMillis() + WIDGET_WINDOW_MS);
                final Consumer<String> listener = sOnWidgetWindow;
                if (listener != null) listener.accept(pkg);
                return false;
            }
        }
        switch (hostingType) {
            case "activity":
            case "next-activity":
            case "next-top-activity":
            case "top-activity":
                sAsleep.remove(pkg);
                sWindowEnd.remove(pkg);
                return false;
            case "broadcast":
            case "content provider":
            case "started-service":
            case "bound-service":
                note("start", pkg, hostingType);
                return true;
            default:
                return false;
        }
    }

    private static boolean isActivity(String hostingType) {
        return hostingType.endsWith("activity");
    }

    public static boolean pushBlocked(String pkg, String action) {
        final LelapPolicy p = sCurrent;
        if (p.mPush.isEmpty() && p.mStrict.isEmpty()) return false;
        if (!"com.google.android.c2dm.intent.RECEIVE".equals(action)) return false;
        if (!p.mPush.contains(pkg) && !p.mStrict.contains(pkg)) return false;
        note("push", pkg, "");
        return true;
    }

    public static boolean jobBlocked(String pkg, String className, String traceTag) {
        final String[][] rules = sCurrent.mJobs;
        if (rules.length == 0) return false;
        for (String[] rule : rules) {
            if (!"*".equals(rule[0]) && !rule[0].equals(pkg)) continue;
            if ((className != null && className.contains(rule[1]))
                    || (traceTag != null && traceTag.contains(rule[1]))) {
                note("job", pkg, traceTag != null ? traceTag : className);
                return true;
            }
        }
        return false;
    }

    /** Records a denial; the same one is kept once per ten minutes. */
    static void note(String kind, String pkg, String detail) {
        final long now = SystemClock.elapsedRealtime();
        final String key = kind + '\t' + pkg + '\t' + detail;
        synchronized (sEventLock) {
            final Long last = sLastEvent.get(key);
            if (last != null && now - last < EVENT_REPEAT_MS) return;
            if (sLastEvent.size() > MAX_EVENTS) sLastEvent.clear();
            sLastEvent.put(key, now);
            if (sEvents.size() >= MAX_EVENTS) sEvents.removeFirst();
            sEvents.addLast(System.currentTimeMillis() + "\t" + key);
        }
    }

    static List<String> events() {
        synchronized (sEventLock) {
            return new ArrayList<>(sEvents);
        }
    }

    static void clearEvents() {
        synchronized (sEventLock) {
            sEvents.clear();
            sLastEvent.clear();
        }
    }

}
