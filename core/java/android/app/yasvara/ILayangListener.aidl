/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.app.yasvara.LayangWindowInfo;

/**
 * Receives the Layang window list whenever a window opens, closes, moves, changes mode or
 * focus. Used by the status-bar chip and Pentas.
 *
 * @hide
 */
oneway interface ILayangListener {
    void onWindowsChanged(in List<LayangWindowInfo> windows);

    /** A window was auto-parked because of memory pressure (§5.6). */
    void onAutoParked(int taskId);
}
