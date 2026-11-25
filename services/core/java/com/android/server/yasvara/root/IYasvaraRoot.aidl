/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.root;

/**
 * The control plane of the ROM's native root, published by system_server as
 * "yasvara_root". Every call needs yasvara.permission.MANAGE_ROOT (signature: Laras and
 * other platform-signed apps); changes also need the device unlocked. The data plane is the
 * socket of yasvara_rootd, not this interface.
 *
 * Compiled into both services.core and Laras (filegroup yasvara-root-aidl), so it carries no
 * parcelables: lists travel as "|"-separated lines.
 *
 * @hide
 */
interface IYasvaraRoot {
    /** persist.yasvara.root.enabled: whether yasvara_rootd runs. */
    boolean isEnabled();
    boolean setEnabled(boolean enabled);

    /** Bits: 1 enabled, 2 frozen, 4 daemon running (its socket exists), 8 daemon installed. */
    int getState();
    boolean setFrozen(boolean frozen);

    /** uid|tier|scope|ns|flags|expiresMs|grantedMs|lastUsedMs|packages (tier 1 shell, 2 root). */
    String[] listGrants();

    /**
     * Enrols a package (or the pseudo package "shell" for adb). tier 1 shell / 2 root, scope
     * 0 always / 1 until reboot / 2 timed (durationMs), ns 0 requester / 1 global, flags as
     * GRANT_FLAG_*.
     */
    boolean setGrant(String packageName, int userId, int tier, int scope, long durationMs,
            int ns, int flags);
    boolean revoke(int uid);
    void revokeAll();

    /** Newest first: "ts|EVENT|uid|pid|tier-or-code|packages|detail". */
    String[] getAudit(int limit);

    /** Refusals since boot, newest first: "uid|package|lastMs|count|reason". */
    String[] getDenied();
    void clearDenied();

    /** Connects to the daemon as system_server and reports "ok v1 …" or what is wrong. */
    String selfTest();

    /** Keys: idle_days (0 = never), notify_denied (0/1), log_full (0/1). */
    int getOption(String key);
    boolean setOption(String key, int value);

    // ---- Akar Module Engine, Bootguard & Native Zygisk (SPEC-0049) ----

    /** JSON array of installed Akar modules (Magisk/KSU/Zygisk). */
    String listModulesJson();

    /**
     * Installs a module ZIP staged inside Laras's private cache directory
     * (`akar_install/<fileName>`). Returns "<exitCode>\n<log>".
     */
    String installModuleFromCache(String fileName);

    boolean setModuleEnabled(String id, boolean enabled);
    boolean setModuleRemove(String id, boolean remove);

    /** Runs a module's `action.sh` and returns "<exitCode>\n<output>". */
    String runModuleAction(String id);

    /** JSON Bootguard & Safe Mode status snapshot. */
    String getBootguardStatusJson();
    boolean clearSafeMode();

    /** JSON Native Zygisk & TabirUnmount config snapshot. */
    String getZygiskConfigJson();
    boolean setZygiskConfigJson(String configJson);

    /** Runs a WebUI X `ksu.exec()` command scoped to `moduleId`. Returns "<exitCode>\n<output>". */
    String execModuleShell(String moduleId, String command);
}
