/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.app.yasvara.LayangTaskState;
import android.graphics.Rect;
import android.os.Bundle;

/**
 * The Shell half of Layang, implemented by LayangController in SystemUI and registered with
 * {@code ILayangManager#registerShell} at Shell init. The service forwards
 * requests here so that Shell can animate them; when no Shell is registered the service
 * applies them in WM core without animation.
 *
 * @hide
 */
oneway interface ILayangShell {
    /** Puts an existing task into Layang at bounds (Recents "Floating window"). */
    void enterLayang(int taskId, in LayangTaskState state, in Rect bounds, in Bundle opts);

    void setMode(int taskId, int mode);

    void setFlags(int taskId, int flags, int mask);

    /** Leaves Layang: the task becomes the fullscreen task. */
    void toFullscreen(int taskId);

    void close(int taskId);

    void closeAll();

    /** Shows the window menu of the task, as if its pill were tapped. */
    void showMenu(int taskId);
}
