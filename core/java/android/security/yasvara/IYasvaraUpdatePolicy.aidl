/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.content.IntentSender;

/**
 * Per-app update policy, implemented by YasvaraUpdatePolicyService in
 * system_server under "yasvara_update_policy". Every call requires
 * yasvara.permission.MANAGE_UPDATE_POLICY, plus INTERACT_ACROSS_USERS_FULL when userId
 * is not the caller's own user. Policy values are the YasvaraUpdatePolicy.POLICY_* ints.
 *
 * @hide
 */
interface IYasvaraUpdatePolicy {
    /** Policy for pkg; POLICY_AUTO when it has no rule. */
    int getPolicy(String pkg, int userId);

    /** Delay in days for a POLICY_DELAY rule, else 0. */
    int getDelayDays(String pkg, int userId);

    /**
     * Sets the policy for pkg. delayDays is used only with POLICY_DELAY and is clamped to
     * 1..365. POLICY_AUTO removes the rule.
     */
    void setPolicy(String pkg, int policy, int delayDays, int userId);

    /** Packages with a rule other than POLICY_AUTO. */
    String[] getManagedPackages(int userId);

    /**
     * Lets the next store update of pkg through a POLICY_NEVER rule, whatever its version, for
     * YasvaraUpdatePolicy.ALLOW_ONCE_WINDOW_MS; the grant ends once that update is installed.
     * Any non-zero versionCode grants; 0 revokes.
     */
    void allowOnce(String pkg, long versionCode, int userId);

    /** Deprecated: always 0. Use getAllowOnceUntil. */
    long getAllowOnceVersion(String pkg, int userId);

    /**
     * Recent install attempts for pkg, newest first, one per line:
     * "timeMillis|fromVersionCode|toVersionCode|installer|decision".
     */
    String[] getHistory(String pkg, int userId);

    /** When versionCode of pkg was first offered by an installer, or 0 if never seen. */
    long getFirstSeen(String pkg, long versionCode, int userId);

    // ---- Version vault ----

    /**
     * Saved versions of pkg, newest first, one per line:
     * "versionCode|versionName|bytes|savedAtMillis".
     */
    String[] listVault(String pkg, int userId);

    /** Deletes one saved version of pkg, or all of them when versionCode is 0. */
    void deleteVault(String pkg, long versionCode, int userId);

    /** Bytes used by the vault of userId. */
    long getVaultBytes(int userId);

    /**
     * Reinstalls a saved version over the installed one, keeping the store as installer of
     * record. clearData wipes the app's data first (newer data can crash an older app);
     * pin sets the app to POLICY_NEVER so the store does not update it straight back.
     * The PackageInstaller result is sent to statusReceiver.
     */
    void rollback(String pkg, long versionCode, boolean clearData, boolean pin, int userId,
            in IntentSender statusReceiver);

    // ---- Update review ----

    /**
     * What the update waiting for confirmation would change, one fact per line
     * ("kind|value|..."), or empty when there is none. Readable by holders of
     * MANAGE_UPDATE_POLICY or INSTALL_PACKAGES (the package installer UI).
     */
    String[] getReview(String pkg, int userId);

    // ---- Added after M3; append only (PackageInstaller calls getReview by number) ----

    /**
     * Saves the installed version of pkg before each store update even on POLICY_AUTO
     * (§4.4 opt-in). Apps with any other policy are always saved.
     */
    void setVaultEnabled(String pkg, boolean enabled, int userId);

    /** True when the installed version of pkg is saved before store updates. */
    boolean isVaultEnabled(String pkg, int userId);

    /** When the current policy of pkg was set, or 0 for POLICY_AUTO / unknown. */
    long getPolicySince(String pkg, int userId);

    /** Clears the data of pkg (the "clear data?" notice after a rollback crash, §4.4). */
    void clearAppData(String pkg, int userId);

    // ---- Pause modes, Allow once, Skip version ----

    /**
     * For a POLICY_NEVER rule: false (default) hands pkg to Laras as update owner so stores
     * that honour update ownership stop offering updates; true leaves the store's owner, so it
     * keeps listing updates and fails to install them.
     */
    void setShownInStore(String pkg, boolean shown, int userId);

    /** True when a POLICY_NEVER pkg is left visible to its store (see setShownInStore). */
    boolean isShownInStore(String pkg, int userId);

    /** When the current Allow once grant of pkg expires (wall clock), or 0 for none. */
    long getAllowOnceUntil(String pkg, int userId);

    /** Sets POLICY_SKIP: versionCode is refused, any later version installs. */
    void skipVersion(String pkg, long versionCode, int userId);

    /** The versionCode a POLICY_SKIP rule refuses, else 0. */
    long getSkippedVersion(String pkg, int userId);
}
