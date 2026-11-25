/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.swara;

import android.app.ActivityManager;
import android.app.yasvara.Swara;
import android.media.AudioDeviceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.List;

/** {@code cmd yasvara_swara}. */
final class SwaraShellCommand extends ShellCommand {
    private final SwaraService mService;

    SwaraShellCommand(SwaraService service) {
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
            final int userId = ActivityManager.getCurrentUser();
            switch (cmd) {
                case "list":
                    final List<Bundle> apps = mService.getApps(userId);
                    for (Bundle b : apps) {
                        pw.println(b.getString(Swara.KEY_PACKAGE) + " uid="
                                + b.getInt(Swara.KEY_UID)
                                + (b.getBoolean(Swara.KEY_PLAYING) ? " playing" : "")
                                + (b.getBoolean(Swara.KEY_RECORDING) ? " recording" : "")
                                + " volume=" + b.getFloat(Swara.KEY_VOLUME)
                                + (b.getBoolean(Swara.KEY_MUTED) ? " muted" : "")
                                + " mic=" + b.getFloat(Swara.KEY_MIC_GAIN)
                                + (b.getBoolean(Swara.KEY_MIC_MUTED) ? " mic-muted" : "")
                                + (b.getBoolean(Swara.KEY_SHARE_MIC) ? " share-mic" : "")
                                + " output=" + b.getInt(Swara.KEY_OUTPUT_TYPE)
                                + (b.getBoolean(Swara.KEY_OUTPUT_AVAILABLE) ? ""
                                        : " (unavailable)"));
                    }
                    if (apps.isEmpty()) pw.println("No app is playing sound");
                    return 0;
                case "set":
                    return set(pw, userId);
                case "reset": {
                    final String target = getNextArgRequired();
                    if ("all".equals(target)) {
                        mService.resetAll(userId);
                    } else {
                        mService.resetRules(target, userId);
                    }
                    return 0;
                }
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

    /** set &lt;pkg&gt; volume 0.4 | mute on | mic mute on | mic gain 1.5 | share-mic on | ... */
    private int set(PrintWriter pw, int userId) {
        final String pkg = getNextArgRequired();
        final Bundle b = new Bundle();
        String key;
        while ((key = getNextArg()) != null) {
            switch (key) {
                case "volume":
                    b.putFloat(Swara.KEY_VOLUME, Float.parseFloat(getNextArgRequired()));
                    break;
                case "mute":
                    b.putBoolean(Swara.KEY_MUTED, on(getNextArgRequired()));
                    break;
                case "mic": {
                    final String what = getNextArgRequired();
                    if ("mute".equals(what)) {
                        b.putBoolean(Swara.KEY_MIC_MUTED, on(getNextArgRequired()));
                    } else if ("gain".equals(what)) {
                        b.putFloat(Swara.KEY_MIC_GAIN, Float.parseFloat(getNextArgRequired()));
                    } else {
                        throw new IllegalArgumentException("mic mute|gain");
                    }
                    break;
                }
                case "share-mic":
                    b.putBoolean(Swara.KEY_SHARE_MIC, on(getNextArgRequired()));
                    break;
                case "reset-on-close":
                    b.putBoolean(Swara.KEY_RESET_ON_CLOSE, on(getNextArgRequired()));
                    break;
                case "output": {
                    final String to = getNextArgRequired();
                    final int type;
                    switch (to) {
                        case "default": type = 0; break;
                        case "speaker": type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER; break;
                        case "earpiece": type = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE; break;
                        case "bt": type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP; break;
                        case "wired": type = AudioDeviceInfo.TYPE_WIRED_HEADSET; break;
                        case "usb": type = AudioDeviceInfo.TYPE_USB_HEADSET; break;
                        default: type = Integer.parseInt(to);
                    }
                    b.putInt(Swara.KEY_OUTPUT_TYPE, type);
                    b.putString(Swara.KEY_OUTPUT_ADDRESS, "");
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown key " + key);
            }
        }
        mService.setRules(pkg, userId, b);
        pw.println(pkg + " updated");
        return 0;
    }

    private static boolean on(String v) {
        return "on".equals(v) || "1".equals(v) || "true".equals(v);
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Swara per-app audio");
        pw.println("  list                          apps playing, recording or with a rule");
        pw.println("  set <pkg> [volume 0..1] [mute on|off] [mic mute on|off] [mic gain 0..2]");
        pw.println("            [share-mic on|off] [reset-on-close on|off]");
        pw.println("            [output default|speaker|earpiece|bt|wired|usb|<type>]");
        pw.println("  reset <pkg>|all");
        pw.println("  dump");
    }
}
