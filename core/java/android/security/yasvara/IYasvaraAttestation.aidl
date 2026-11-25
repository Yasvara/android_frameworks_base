/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

/**
 * System-side attestation protection. Implemented by YasvaraAttestationService in
 * system_server; called from keystore hooks in target app processes and from Laras.
 *
 * <p>Chains travel as newline-joined base64 DER (leaf first), capped at 64 KiB each way.
 * The private key never crosses this interface in either direction.
 *
 * @hide
 */
interface IYasvaraAttestation {
    /**
     * Runs the ladder for pkg/uid and returns the chain the caller should use
     * (rewritten or the input unchanged). The service verifies pkg belongs to the
     * calling uid; mismatches return the input unchanged.
     */
    String protectChain(String pkg, int uid, String base64Chain);

    /** Last rung report for pkg as JSON {rung, at, reason}, or "" when none. */
    String getRung(String pkg);

    /** Installed keybox descriptor as JSON, or "" when none. */
    String getKeyboxInfo();

    /** Installs a keybox from its XML. Requires yasvara.permission.WRITE_ATTESTATION. */
    void setKeybox(String xml);

    /** Removes the installed keybox. Requires yasvara.permission.WRITE_ATTESTATION. */
    void clearKeybox();

    /**
     * Synthesizes an attestation certificate chain for a newly generated key.
     * Returns newline-joined base64 DER certificates (leaf first), or empty string on failure.
     */
    String synthesizeChain(String pkg, int uid, in byte[] requestData);

    /**
     * SIGKILLs every running process of the given packages (all users) from system_server,
     * so spoof settings read at process start take effect on the next launch. Matches the
     * package's own processes by name (pkg, pkg:*, pkg.*) or package list, uid-checked, so
     * DroidGuard (com.google.android.gms.unstable), com.google.process.gapps and bound or
     * foreground processes are included. Never force-stops (alarms, jobs and FCM survive).
     * Only app uids are touched. Requires yasvara.permission.WRITE_ATTESTATION.
     *
     * @return number of processes killed.
     */
    int restartPackages(in String[] packages);
}
