/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.app.PendingIntent;
import android.app.yasvara.ILayangListener;
import android.app.yasvara.ILayangShell;
import android.app.yasvara.LayangTaskState;
import android.app.yasvara.LayangWindowInfo;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;

/**
 * Layang floating windows, implemented by YasvaraLayangService in
 * system_server under "yasvara_layang". Every call requires yasvara.permission.MANAGE_LAYANG.
 * Option and rule bundles use the Layang.OPT_* and Layang.RULE_* keys.
 *
 * @hide
 */
interface ILayangManager {
    /** Opens component as a floating window. Returns a Layang.RESULT_* code. */
    int openApp(in ComponentName component, int userId, in Bundle opts);

    /** Puts an existing task (for example a Recents card) into a floating window. */
    int openTask(int taskId, in Bundle opts);

    /** Sends pi into a floating window (notification → floating). */
    int openPendingIntent(in PendingIntent pi, in Bundle opts);

    void setMode(int taskId, int mode);

    /** Sets the LayangTaskState.FLAG_* bits in mask to flags. */
    void setFlags(int taskId, int flags, int mask);

    void toFullscreen(int taskId);

    void close(int taskId);

    void closeAll();

    List<LayangWindowInfo> getWindows();

    void registerListener(ILayangListener listener);

    void unregisterListener(ILayangListener listener);

    /** Volume 0..1 applied to every player of the task's uid; muted wins over volume. */
    void setWindowVolume(int taskId, float volume, boolean muted);

    /** Game packages of userId, pushed by Pentas; replaces the previous list. */
    void setGamePackages(in String[] packages, int userId);

    boolean isGame(String packageName, int userId);

    Bundle getRules(String packageName, int userId);

    void setRules(String packageName, int userId, in Bundle rules);

    /** Packages with any rule, for the Laras per-app list. */
    String[] getRulePackages(int userId);

    /** Called by SystemUI at Shell init; replaces any previous Shell. */
    void registerShell(ILayangShell shell);

    /**
     * Bounds for a window of packageName opened in the given mode at the given orientation
     * class (Configuration.ORIENTATION_*), from the stored geometry or the defaults (§6.4).
     */
    Rect getLaunchBounds(String packageName, int userId, int mode, int orientation);
}
