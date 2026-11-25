/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.app.yasvara.ISwaraListener;
import android.os.Bundle;

/**
 * Swara per-app audio, implemented by SwaraService in system_server under
 * "yasvara_swara". Every call requires yasvara.permission.MANAGE_SWARA. Rule bundles use the
 * Swara.KEY_* keys; a key left out keeps its value.
 *
 * @hide
 */
interface ISwaraManager {
    /** The package's rule; defaults when it has none. */
    Bundle getRules(String packageName, int userId);

    /** Merges rules into the package's rule and applies it right away. */
    void setRules(String packageName, int userId, in Bundle rules);

    void resetRules(String packageName, int userId);

    void resetAll(int userId);

    /**
     * Apps playing or recording now plus apps with a rule: a Bundle per app with
     * Swara.KEY_PACKAGE, KEY_UID, KEY_PLAYING, KEY_RECORDING and every rule key.
     * Playing first, then recording, then rule only.
     */
    List<Bundle> getApps(int userId);

    void registerListener(ISwaraListener listener);

    void unregisterListener(ISwaraListener listener);
}
