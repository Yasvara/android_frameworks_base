/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

/**
 * Swara: the live app list or a rule changed; call ISwaraManager.getApps again.
 *
 * @hide
 */
oneway interface ISwaraListener {
    void onAppsChanged(int userId);
}
