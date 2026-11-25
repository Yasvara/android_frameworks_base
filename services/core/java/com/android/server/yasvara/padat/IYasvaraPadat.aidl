/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.padat;

/**
 * Control plane for Padat realtime per-app F2FS compression.
 *
 * Served by {@link PadatService} in {@code system_server} under {@code "yasvara_padat"}
 * (and shell alias {@code "padat"}). Every method requires {@code yasvara.permission.MANAGE_PADAT}
 * (signature-protected, held by Laras) or root/shell.
 */
interface IYasvaraPadat {
    /** Kernel F2FS compression status, saved blocks, battery temp, and live job state (JSON). */
    String getStatusJson();

    /** Current `/data/system/padat/policy.json` (JSON). */
    String getPolicyJson();

    /** Validates and atomically saves policy JSON; returns 0 or negative errno. */
    int setPolicyJson(String json);

    /** Fast stat-only summary across all installed packages (JSON array). */
    String getAllStatsJson();

    /** Dry-run Smart Probe scan of a single package (JSON). */
    String scanPackageJson(String pkg, int algo, int level, int clusterKb, int scopeMask);

    /** Atomically compresses eligible files in `pkg` and sets dir inheritance (JSON report). */
    String compressPackageJson(String pkg, int algo, int level, int clusterKb,
            int scopeMask, boolean allowRunning);

    /** Atomically decompresses `pkg` and clears dir inheritance (JSON report). */
    String decompressPackageJson(String pkg, int scopeMask, boolean allowRunning);

    /** Recursively calls `F2FS_IOC_RELEASE_COMPRESS_BLOCKS` under `path` (JSON report). */
    String releasePathJson(String path);

    /** Force-stops `pkg` before compression when user chooses "Force-Stop & Compress". */
    void forceStopPackage(String pkg);

    /** Manually pauses or resumes active compression work. */
    void setPaused(boolean paused);

    /** Cancels any active compression/decompression job at the next chunk boundary. */
    void cancelJob();
}
