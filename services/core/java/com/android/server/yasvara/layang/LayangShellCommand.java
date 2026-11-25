/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.layang;

import android.app.ActivityManager;
import android.app.yasvara.Layang;
import android.app.yasvara.LayangTaskState;
import android.app.yasvara.LayangWindowInfo;
import android.content.ComponentName;
import android.os.Binder;
import android.os.Bundle;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.List;

/** {@code cmd layang}. */
final class LayangShellCommand extends ShellCommand {
    private final YasvaraLayangService mService;

    LayangShellCommand(YasvaraLayangService service) {
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
                case "open":
                    return open(pw);
                case "list":
                    return list(pw);
                case "mode":
                    return mode(pw);
                case "ghost":
                    return ghost(pw);
                case "lock":
                    return flag(pw, LayangTaskState.FLAG_LOCKED);
                case "close":
                    return close(pw);
                case "rules":
                    return rules(pw);
                case "games":
                    return games(pw);
                case "volume":
                    return volume(pw);
                case "dump":
                    mService.dumpInternal(pw);
                    return 0;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println("Error: " + e.getMessage());
            return 1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int open(PrintWriter pw) {
        int userId = ActivityManager.getCurrentUser();
        final Bundle opts = new Bundle();
        String target = null;
        String arg;
        while ((arg = getNextArg()) != null) {
            switch (arg) {
                case "--user":
                    userId = Integer.parseInt(getNextArgRequired());
                    break;
                case "--scale":
                    opts.putFloat(Layang.OPT_SCALE, Float.parseFloat(getNextArgRequired()));
                    break;
                case "--at": {
                    final String[] xy = getNextArgRequired().split(",");
                    if (xy.length != 2) throw new IllegalArgumentException("--at X,Y");
                    opts.putInt(Layang.OPT_X, Integer.parseInt(xy[0].trim()));
                    opts.putInt(Layang.OPT_Y, Integer.parseInt(xy[1].trim()));
                    break;
                }
                case "--mini":
                    opts.putBoolean(Layang.OPT_MINI, true);
                    break;
                default:
                    target = arg;
            }
        }
        if (target == null) throw new IllegalArgumentException("open <pkg>[/<activity>]");
        final ComponentName cn;
        if (target.contains("/")) {
            cn = ComponentName.unflattenFromString(target);
            if (cn == null) throw new IllegalArgumentException("bad component " + target);
        } else {
            cn = new ComponentName(target, "");
        }
        final int res = mService.openAppInternal(cn, userId, opts);
        pw.println(resultToString(res));
        return res == Layang.RESULT_OK ? 0 : 1;
    }

    private int list(PrintWriter pw) {
        final List<LayangWindowInfo> windows = mService.windowsForShell();
        if (windows.isEmpty()) {
            pw.println("no Layang windows");
            return 0;
        }
        for (LayangWindowInfo w : windows) {
            pw.println(w.taskId + "\t" + w.packageName + "\t"
                    + LayangTaskState.modeToString(w.state.getMode())
                    + (w.state.isGame() ? "\tgame" : "\tapp")
                    + (w.state.isGhost() ? "\tghost" : "")
                    + (w.state.hasFlag(LayangTaskState.FLAG_LOCKED) ? "\tlocked" : "")
                    + "\t" + w.bounds.toShortString() + (w.focused ? "\tfocused" : ""));
        }
        return 0;
    }

    private int mode(PrintWriter pw) {
        final int taskId = Integer.parseInt(getNextArgRequired());
        final String m = getNextArgRequired();
        if ("fullscreen".equals(m)) {
            mService.toFullscreenInternal(taskId);
            return 0;
        }
        final int mode = LayangTaskState.modeFromString(m);
        if (mode < 0) throw new IllegalArgumentException("mode floating|mini|parked|fullscreen");
        mService.setModeInternal(taskId, mode);
        return 0;
    }

    private int ghost(PrintWriter pw) {
        final int taskId = Integer.parseInt(getNextArgRequired());
        final boolean on = parseOnOff(getNextArgRequired());
        final String alpha = getNextArg();
        if (alpha != null) {
            final int pct = Math.max(20, Math.min(80, Integer.parseInt(alpha)));
            android.provider.Settings.Secure.putIntForUser(
                    mServiceContextResolver(), Layang.KEY_GHOST_ALPHA, pct,
                    ActivityManager.getCurrentUser());
        }
        mService.setFlagsInternal(taskId, on ? LayangTaskState.FLAG_GHOST : 0,
                LayangTaskState.FLAG_GHOST);
        return 0;
    }

    private int flag(PrintWriter pw, int flag) {
        final int taskId = Integer.parseInt(getNextArgRequired());
        final boolean on = parseOnOff(getNextArgRequired());
        mService.setFlagsInternal(taskId, on ? flag : 0, flag);
        return 0;
    }

    private int close(PrintWriter pw) {
        final String t = getNextArgRequired();
        if ("all".equals(t)) {
            mService.closeAllInternal();
        } else {
            mService.closeInternal(Integer.parseInt(t));
        }
        return 0;
    }

    private int rules(PrintWriter pw) {
        final String pkg = getNextArgRequired();
        final String what = getNextArg();
        final int userId = ActivityManager.getCurrentUser();
        if (what == null) {
            final LayangStore.App a = mService.store().get(userId, pkg);
            pw.println(a == null ? "no rules" : "always=" + a.always + " blocked=" + a.blocked
                    + " reflow=" + a.readable + " aspectLock=" + a.aspectLock
                    + " size=" + a.size + " game=" + a.game
                    + " fps=" + a.fps + " res=" + (a.resScale > 0f ? a.resScale : 1f));
            return 0;
        }
        final Bundle b = new Bundle();
        switch (what) {
            case "always":
                b.putBoolean(Layang.RULE_ALWAYS, parseOnOff(getNextArgRequired()));
                break;
            case "block":
                b.putBoolean(Layang.RULE_BLOCKED, parseOnOff(getNextArgRequired()));
                break;
            case "reflow":
                b.putInt(Layang.RULE_READABLE, parseTri(getNextArgRequired()));
                break;
            case "aspect-lock":
                b.putInt(Layang.RULE_ASPECT_LOCK, parseTri(getNextArgRequired()));
                break;
            case "game": {
                final int v = parseTri(getNextArgRequired());
                if (v < 0) {
                    b.putString(Layang.RULE_GAME, "default");
                } else {
                    b.putBoolean(Layang.RULE_GAME, v == 1);
                }
                break;
            }
            case "size":
                b.putString(Layang.RULE_SIZE, getNextArgRequired().toUpperCase());
                break;
            case "fps":
                b.putInt(Layang.RULE_FPS, Integer.parseInt(getNextArgRequired()));
                break;
            case "res":
                b.putFloat(Layang.RULE_RES_SCALE, Float.parseFloat(getNextArgRequired()));
                break;
            case "reset":
                b.putBoolean(Layang.RULE_RESET_GEOMETRY, true);
                break;
            default:
                throw new IllegalArgumentException(
                        "rules <pkg> always|block|reflow|aspect-lock|game|size|fps|res|reset ...");
        }
        mService.setRulesInternal(pkg, userId, b);
        return 0;
    }

    private int games(PrintWriter pw) {
        final String list = getNextArg();
        final int userId = ActivityManager.getCurrentUser();
        if (list == null) {
            pw.println("usage: games <pkg,pkg,...> (replaces the Pentas list)");
            return 1;
        }
        mService.setGamePackages(YasvaraLayangService.split(list), userId);
        return 0;
    }

    private int volume(PrintWriter pw) {
        final int taskId = Integer.parseInt(getNextArgRequired());
        final String v = getNextArgRequired();
        final boolean muted = "mute".equals(v);
        mService.setWindowVolume(taskId, muted ? 0f : Float.parseFloat(v), muted);
        return 0;
    }

    private android.content.ContentResolver mServiceContextResolver() {
        return mService.contextForShell().getContentResolver();
    }

    private static boolean parseOnOff(String s) {
        if ("on".equals(s) || "true".equals(s) || "1".equals(s)) return true;
        if ("off".equals(s) || "false".equals(s) || "0".equals(s)) return false;
        throw new IllegalArgumentException("expected on|off, got " + s);
    }

    /** on → 1, off → 0, default → -1. */
    private static int parseTri(String s) {
        if ("default".equals(s)) return -1;
        return parseOnOff(s) ? 1 : 0;
    }

    private static String resultToString(int res) {
        switch (res) {
            case Layang.RESULT_OK: return "ok";
            case Layang.RESULT_DISABLED: return "disabled (yasvara_layang_enabled=0 or safe mode)";
            case Layang.RESULT_BLOCKED: return "blocked by a rule";
            case Layang.RESULT_NOT_FOUND: return "not found";
            default: return "failed";
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Layang floating windows");
        pw.println("  open <pkg>[/<activity>] [--user N] [--scale S] [--at X,Y] [--mini]");
        pw.println("  list");
        pw.println("  mode <taskId> floating|mini|parked|fullscreen");
        pw.println("  ghost <taskId> on|off [alpha%]");
        pw.println("  lock <taskId> on|off");
        pw.println("  close <taskId>|all");
        pw.println("  rules <pkg> [always|block on|off] [reflow|aspect-lock|game on|off|default]");
        pw.println("              [size S|M|L] [reset]");
        pw.println("  games <pkg,pkg,...>      replace the Pentas game list");
        pw.println("  volume <taskId> <0..1>|mute");
        pw.println("  dump");
    }
}
