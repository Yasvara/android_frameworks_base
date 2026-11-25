/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.saring;

import android.os.ParcelFileDescriptor;

interface IYasvaraSaring {
    /** Layers are "names", "browser" and "web". */
    boolean getLayer(String layer);
    void setLayer(String layer, boolean on);

    /** Stops name filtering for this many seconds; 0 resumes. */
    void pause(int seconds);

    /** Replaces the name table. Returns the names now in use, or -1 when it was refused. */
    long install(in ParcelFileDescriptor table);

    /** Lists are "exempt" (packages), "browsers" (packages), "popups" and "notifications" (sites). */
    String[] getList(String list);
    void setList(String list, in String[] values);

    /** Lookups checked, lookups refused, names, table time in seconds, paused until (wall ms). */
    long[] getStats();

    /** Refused lookups, oldest first: "wallTimeMs\tuid\tcount\tname". */
    List<String> getLog();
    void clearLog();

    /**
     * The name table, read-only, for the caller's WebViews. Null when the web layer is off,
     * the caller is exempt or no table is installed. Needs no permission. Keep it last:
     * android.webkit calls it by number.
     */
    ParcelFileDescriptor openTable();
}
