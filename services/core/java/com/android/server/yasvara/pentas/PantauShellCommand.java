/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.pentas;

import android.annotation.Nullable;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.ShellCommand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import yasvara.pantau.IPantau;

/**
 * {@code cmd pantau} — AI-native structured JSON/CLI router.
 *
 * Lightweight queries ({@code status} when off, {@code uijank summary}) answer directly from
 * {@code system_server} without waking {@code pantaud}; live commands ({@code snap}, {@code hud})
 * wake the lazy daemon on demand.
 */
final class PantauShellCommand extends ShellCommand {
    private final PentasService mService;

    PantauShellCommand(PentasService service) {
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
                    return cmdStatus(pw);
                case "snap":
                    return cmdSnap(pw);
                case "uijank":
                    return cmdUiJank(pw);
                case "hud":
                    return cmdHud(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("{\"v\":1,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
            return 1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int cmdStatus(PrintWriter pw) throws Exception {
        final IPantau p = mService.peekPantau();
        if (p == null) {
            final JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("state", "off");
            o.put("daemon_running", false);
            o.put("top_pkg", mService.topPackage());
            pw.println(o.toString());
            return 0;
        }
        pw.print(p.query("status"));
        return 0;
    }

    private int cmdSnap(PrintWriter pw) throws Exception {
        final IPantau p = mService.requirePantau();
        if (p == null) {
            pw.println("{\"v\":1,\"error\":\"pantaud_unavailable\"}");
            return 1;
        }
        pw.print(p.query("snap"));
        return 0;
    }

    /**
     * {@code cmd pantau uijank summary [--pkg <pkg> | --all] [--top N] [--json]}
     * Reads Android's live {@code graphicsstats} service (zero extra overhead) and emits a ranked
     * JSON table of UI jank across packages.
     */
    private int cmdUiJank(PrintWriter pw) throws Exception {
        final String sub = getNextArg();
        if (!"summary".equals(sub)) {
            pw.println("{\"v\":1,\"error\":\"usage: cmd pantau uijank summary [--pkg PKG|--all] [--top N] [--json]\"}");
            return 2;
        }
        String filterPkg = null;
        int top = 15;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--pkg":
                    filterPkg = getNextArgRequired();
                    break;
                case "--all":
                    filterPkg = null;
                    break;
                case "--top":
                    top = Math.max(1, Integer.parseInt(getNextArgRequired()));
                    break;
                case "--json":
                    break;
                default:
                    break;
            }
        }

        final List<UiJankRow> rows = readGraphicsStats(filterPkg);
        rows.sort(Comparator.comparingDouble((UiJankRow r) -> r.jankPct).reversed()
                .thenComparingInt(r -> -r.p99Ms));

        final JSONArray arr = new JSONArray();
        final int limit = Math.min(rows.size(), top);
        for (int i = 0; i < limit; i++) {
            final UiJankRow r = rows.get(i);
            final JSONObject o = new JSONObject();
            o.put("pkg", r.pkg);
            o.put("uid", r.uid);
            o.put("frames", r.totalFrames);
            o.put("janky_frames", r.jankyFrames);
            o.put("janky_pct", Math.round(r.jankPct * 100.0) / 100.0);
            o.put("p50_ms", r.p50Ms);
            o.put("p90_ms", r.p90Ms);
            o.put("p95_ms", r.p95Ms);
            o.put("p99_ms", r.p99Ms);
            o.put("missed_vsync", r.missedVsync);
            o.put("high_input_latency", r.highInputLatency);
            o.put("slow_ui", r.slowUi);
            o.put("slow_sync", r.slowSync);
            o.put("slow_rt", r.slowRt);
            o.put("deadline_missed", r.deadlineMissed);
            arr.put(o);
        }
        final JSONObject root = new JSONObject();
        root.put("v", 1);
        root.put("source", "graphicsstats");
        root.put("count", arr.length());
        root.put("packages", arr);
        pw.println(root.toString());
        return 0;
    }

    private int cmdHud(PrintWriter pw) throws Exception {
        final String action = getNextArg();
        if (action == null) {
            pw.println("{\"v\":1,\"error\":\"usage: cmd pantau hud show|hide|pin|unpin|move W X Y|profile RAW\"}");
            return 2;
        }
        if ("move".equals(action)) {
            final int w = Integer.parseInt(getNextArgRequired());
            final int x = Integer.parseInt(getNextArgRequired());
            final int y = Integer.parseInt(getNextArgRequired());
            final IPantau p = mService.peekPantau();
            if (p != null) p.moveWidget(w, x, y);
            pw.println("{\"v\":1,\"ok\":true,\"widget\":" + w + ",\"x\":" + x + ",\"y\":" + y + "}");
            return 0;
        }
        if ("profile".equals(action)) {
            final String raw = getNextArgRequired();
            mService.setHudProfileSetting(raw);
            pw.println("{\"v\":1,\"ok\":true,\"profile\":" + JSONObject.quote(raw) + "}");
            return 0;
        }
        final boolean pin = "pin".equals(action) || "show".equals(action);
        final boolean unpin = "unpin".equals(action) || "hide".equals(action);
        if (!pin && !unpin) {
            pw.println("{\"v\":1,\"error\":\"unknown_hud_action\"}");
            return 2;
        }
        final IPantau p = pin ? mService.requirePantau() : mService.peekPantau();
        if (p != null) {
            if (pin && mService.topTaskId() >= 0) {
                p.session(mService.topTaskId(), 0, false);
            }
            p.event(8 /* HudPin */, pin ? 1L : 0L, 0L);
        }
        pw.println("{\"v\":1,\"ok\":true,\"hud_pinned\":" + pin + "}");
        return 0;
    }

    private static List<UiJankRow> readGraphicsStats(@Nullable String filterPkg) {
        final List<UiJankRow> out = new ArrayList<>();
        final IBinder svc = ServiceManager.getService("graphicsstats");
        if (svc == null) return out;

        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor writeEnd = pipe[1];
            final Thread dumper = new Thread(() -> {
                try {
                    svc.dump(writeEnd.getFileDescriptor(), new String[0]);
                } catch (Exception ignored) {
                } finally {
                    try {
                        writeEnd.close();
                    } catch (Exception ignored) {
                    }
                }
            }, "pantau-gfxstats");
            dumper.start();

            UiJankRow cur = null;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("UID: ")) {
                        if (cur != null && cur.totalFrames > 0
                                && (filterPkg == null || filterPkg.equals(cur.pkg))) {
                            out.add(cur);
                        }
                        cur = new UiJankRow();
                        cur.uid = parseLeadingInt(line.substring(5));
                    } else if (cur != null) {
                        if (line.startsWith("Package: ")) {
                            cur.pkg = line.substring(9).trim();
                        } else if (line.startsWith("Total frames rendered: ")) {
                            cur.totalFrames = parseLeadingInt(line.substring(23));
                        } else if (line.startsWith("Janky frames: ")) {
                            cur.jankyFrames = parseLeadingInt(line.substring(14));
                            cur.jankPct = cur.totalFrames > 0
                                    ? (cur.jankyFrames * 100.0) / cur.totalFrames : 0.0;
                        } else if (line.startsWith("50th percentile: ")) {
                            cur.p50Ms = parseLeadingInt(line.substring(17));
                        } else if (line.startsWith("90th percentile: ")) {
                            cur.p90Ms = parseLeadingInt(line.substring(17));
                        } else if (line.startsWith("95th percentile: ")) {
                            cur.p95Ms = parseLeadingInt(line.substring(17));
                        } else if (line.startsWith("99th percentile: ")) {
                            cur.p99Ms = parseLeadingInt(line.substring(17));
                        } else if (line.startsWith("Number Missed Vsync: ")) {
                            cur.missedVsync = parseLeadingInt(line.substring(21));
                        } else if (line.startsWith("Number High input latency: ")) {
                            cur.highInputLatency = parseLeadingInt(line.substring(27));
                        } else if (line.startsWith("Number Slow UI thread: ")) {
                            cur.slowUi = parseLeadingInt(line.substring(23));
                        } else if (line.startsWith("Number Slow bitmap uploads: ")) {
                            cur.slowSync = parseLeadingInt(line.substring(28));
                        } else if (line.startsWith("Number Slow issue draw commands: ")) {
                            cur.slowRt = parseLeadingInt(line.substring(33));
                        } else if (line.startsWith("Number Frame deadline missed: ")) {
                            cur.deadlineMissed = parseLeadingInt(line.substring(30));
                        }
                    }
                }
            }
            if (cur != null && cur.totalFrames > 0
                    && (filterPkg == null || filterPkg.equals(cur.pkg))) {
                out.add(cur);
            }
            dumper.join(1000);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static int parseLeadingInt(String s) {
        int i = 0;
        final int n = s.length();
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        int v = 0;
        while (i < n) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            i++;
        }
        return v;
    }

    private static final class UiJankRow {
        int uid;
        String pkg = "";
        int totalFrames;
        int jankyFrames;
        double jankPct;
        int p50Ms;
        int p90Ms;
        int p95Ms;
        int p99Ms;
        int missedVsync;
        int highInputLatency;
        int slowUi;
        int slowSync;
        int slowRt;
        int deadlineMissed;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Pantau telemetry & UI jank CLI:");
        pw.println("  cmd pantau status [--json]");
        pw.println("  cmd pantau snap [--json]");
        pw.println("  cmd pantau uijank summary [--pkg PKG | --all] [--top N] [--json]");
        pw.println("  cmd pantau hud pin|unpin|show|hide");
    }
}
