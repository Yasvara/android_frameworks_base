/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

/**
 * System-side integrity & attestation control plane implemented by YasvaraAttestationService
 * in system_server and backed by Rust (`libyasvara_integrity_jni`).
 *
 * <p>All runtime certificate patching and `PURPOSE_ATTEST_KEY` handling run natively inside
 * Rust `keystore2`; private keys never cross Binder or enter Settings.Secure.
 *
 * @hide
 */
interface IYasvaraAttestation {
    /**
     * Live runtime status JSON `{bootDigestReady, bootSource, keyboxLoaded, rkpManufacturer,
     * pifEnabled, trickystoreEnabled, purgeGen}`. Zero disk I/O.
     */
    String getStatus();

    /** Installed keybox descriptor as JSON, or "" when none. */
    String getKeyboxInfo();

    /**
     * Validates a candidate keybox (raw XML or Base64 XML) using the exact same Rust/BoringSSL
     * parser as `keystore2`. Requires yasvara.permission.WRITE_ATTESTATION.
     *
     * @return JSON `{valid, error, publicChainXml, rkpManufacturer, stamp, infoJson}`.
     */
    String inspectKeybox(String rawXml, boolean requirePrivateKey);

    /** Installs a keybox from its XML or Base64 XML. Requires yasvara.permission.WRITE_ATTESTATION. */
    void setKeybox(String xml);

    /** Removes the installed keybox. Requires yasvara.permission.WRITE_ATTESTATION. */
    void clearKeybox();

    /**
     * Bumps the monotonic integrity cache purge generation and restarts GMS / Play Store / GSF
     * so `YasvaraSpoofEngine` clears stale `dg.db`, `gmscompliance`, and `app_dg_cache` caches
     * cleanly without wiping user accounts. Requires yasvara.permission.WRITE_ATTESTATION.
     *
     * @return number of processes restarted.
     */
    int purgeIntegrityCache();

    /**
     * SIGKILLs every running process of the given packages (all users) from system_server,
     * so spoof settings read at process start take effect on the next launch.
     * Requires yasvara.permission.WRITE_ATTESTATION.
     *
     * @return number of processes killed.
     */
    int restartPackages(in String[] packages);
}
