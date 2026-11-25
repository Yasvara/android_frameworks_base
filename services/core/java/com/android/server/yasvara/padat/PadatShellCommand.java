/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.padat;

import android.os.Binder;
import android.os.ShellCommand;

import org.json.JSONObject;

import java.io.PrintWriter;

/**
 * {@code cmd padat} — SystemServer CLI router for Padat.
 */
final class PadatShellCommand extends ShellCommand {
    private final PadatService mService;

    PadatShellCommand(PadatService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }
        final PrintWriter pw = getOutPrintWriter();
        final long ident = Binder.clearCallingIdentity();
        try {
            switch (cmd) {
                case "status":
                    pw.println(mService.getStatusJson());
                    return 0;
                case "policy":
                    return cmdPolicy(pw);
                case "stats":
                    return cmdStats(pw);
                case "scan":
                    return cmdScanOrCompress(pw, false);
                case "compress":
                    return cmdScanOrCompress(pw, true);
                case "decompress":
                    return cmdDecompress(pw);
                case "sync": {
                    final String pkg = getNextArgRequired();
                    pw.println(mService.syncPackageInternal(pkg));
                    return 0;
                }
                case "release": {
                    final String path = getNextArgRequired();
                    pw.println(mService.releasePathJson(path));
                    return 0;
                }
                case "pause":
                    mService.setPaused(true);
                    pw.println("{\"paused\":true}");
                    return 0;
                case "resume":
                    mService.setPaused(false);
                    pw.println("{\"paused\":false}");
                    return 0;
                case "cancel":
                    mService.cancelJob();
                    pw.println("{\"cancelled\":true}");
                    return 0;
                case "cleanup": {
                    final long n = mService.cleanupStaleTempsInternal();
                    pw.println("{\"removed\":" + n + "}");
                    return 0;
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
            return 1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int cmdPolicy(PrintWriter pw) throws Exception {
        final String sub = getNextArg();
        if (sub == null || "get".equals(sub)) {
            pw.println(mService.getPolicyJson());
            return 0;
        }
        if ("auto-tier1".equals(sub)) {
            final String val = getNextArgRequired();
            final boolean on = "on".equalsIgnoreCase(val) || "true".equalsIgnoreCase(val)
                    || "1".equals(val);
            final JSONObject pol = new JSONObject(mService.getPolicyJson());
            pol.put("auto_tier1", on);
            final int rc = mService.setPolicyJson(pol.toString());
            pw.println("{\"rc\":" + rc + ",\"auto_tier1\":" + on + "}");
            return rc == 0 ? 0 : 1;
        }
        pw.println("{\"error\":\"usage: cmd padat policy [get | auto-tier1 on|off]\"}");
        return 2;
    }

    private int cmdStats(PrintWriter pw) {
        final String pkg = getNextArg();
        if (pkg != null) {
            pw.println(mService.scanPackageJson(pkg, 0, 1, 16, PadatService.SCOPE_FULL));
        } else {
            pw.println(mService.getAllStatsJson());
        }
        return 0;
    }

    private int cmdScanOrCompress(PrintWriter pw, boolean compress) {
        final String pkg = getNextArgRequired();
        int algo = 0; // lz4
        int level = 1;
        int clusterKb = 16;
        int scopeMask = compress ? PadatService.SCOPE_TIER1 : PadatService.SCOPE_FULL;
        boolean allowRunning = false;
        boolean forceStop = false;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--preset": {
                    final String p = getNextArgRequired();
                    if ("balanced".equalsIgnoreCase(p)) {
                        algo = 2; level = 1; clusterKb = 32;
                    } else if ("max".equalsIgnoreCase(p)) {
                        algo = 2; level = 3; clusterKb = 64;
                    } else {
                        algo = 0; level = 1; clusterKb = 16;
                    }
                    break;
                }
                case "--algo":
                    algo = PadatService.algoNameToId(getNextArgRequired());
                    break;
                case "--level":
                    level = Integer.parseInt(getNextArgRequired());
                    break;
                case "--cluster": {
                    String c = getNextArgRequired().toLowerCase();
                    if (c.endsWith("k")) c = c.substring(0, c.length() - 1);
                    clusterKb = Integer.parseInt(c);
                    break;
                }
                case "--tier1":
                    scopeMask = PadatService.SCOPE_TIER1;
                    break;
                case "--full":
                    scopeMask = PadatService.SCOPE_FULL;
                    break;
                case "--internal":
                    scopeMask |= PadatService.SCOPE_INTERNAL;
                    break;
                case "--force-running":
                    allowRunning = true;
                    break;
                case "--force-stop":
                    forceStop = true;
                    break;
                default:
                    break;
            }
        }
        if (compress) {
            if (forceStop) {
                mService.forceStopPackage(pkg);
            }
            pw.println(mService.compressPackageJson(pkg, algo, level, clusterKb, scopeMask,
                    allowRunning));
        } else {
            pw.println(mService.scanPackageJson(pkg, algo, level, clusterKb, scopeMask));
        }
        return 0;
    }

    private int cmdDecompress(PrintWriter pw) {
        final String pkg = getNextArgRequired();
        int scopeMask = PadatService.SCOPE_FULL | PadatService.SCOPE_INTERNAL;
        boolean allowRunning = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("--tier1".equals(opt)) scopeMask = PadatService.SCOPE_TIER1;
            else if ("--force-running".equals(opt)) allowRunning = true;
        }
        pw.println(mService.decompressPackageJson(pkg, scopeMask, allowRunning));
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Padat realtime per-app F2FS compression:");
        pw.println("  cmd padat status");
        pw.println("  cmd padat stats [pkg]");
        pw.println("  cmd padat policy [get | auto-tier1 on|off]");
        pw.println("  cmd padat scan <pkg> [--preset fast|balanced|max] [--tier1|--full]");
        pw.println("  cmd padat compress <pkg> [--preset fast|balanced|max] [--algo lz4|lz4hc|zstd]");
        pw.println("                           [--level 1-9] [--cluster 16k|32k|64k|128k]");
        pw.println("                           [--tier1|--full|--internal] [--force-stop|--force-running]");
        pw.println("  cmd padat decompress <pkg> [--tier1] [--force-running]");
        pw.println("  cmd padat sync <pkg>");
        pw.println("  cmd padat release <path>");
        pw.println("  cmd padat pause | resume | cancel | cleanup");
    }
}
