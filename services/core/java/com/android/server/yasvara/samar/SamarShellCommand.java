/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.samar;

import android.os.ShellCommand;

import java.io.PrintWriter;

/** {@code cmd yasvara_samar}. A trailing package argument targets that app's profile. */
final class SamarShellCommand extends ShellCommand {
    private final SamarService mService;

    SamarShellCommand(SamarService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) return handleDefaultCommands(null);
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "on":
                case "off":
                    mService.shellSetGlobalOn("on".equals(cmd));
                    return 0;
                case "set": {
                    final double lat = Double.parseDouble(getNextArgRequired());
                    final double lng = Double.parseDouble(getNextArgRequired());
                    final double implied = mService.shellPlace(optionalTarget(), lat, lng);
                    if (Double.isNaN(implied)) {
                        getErrPrintWriter().println("invalid point");
                        return 1;
                    }
                    pw.println(String.format("jump %.0f m/s", implied));
                    return 0;
                }
                case "goto": {
                    final double[] p = mService.shellParse(getNextArgRequired());
                    if (p == null) {
                        getErrPrintWriter().println("not a location");
                        return 1;
                    }
                    mService.shellPlace(optionalTarget(), p[0], p[1]);
                    pw.println(p[0] + "," + p[1]);
                    return 0;
                }
                case "joy": {
                    final float x = Float.parseFloat(getNextArgRequired());
                    final float y = Float.parseFloat(getNextArgRequired());
                    mService.shellJoystick(optionalTarget(), x, y);
                    return 0;
                }
                case "speed": {
                    final int preset = Integer.parseInt(getNextArgRequired());
                    mService.shellSpeed(optionalTarget(), preset);
                    return 0;
                }
                case "mode": {
                    final String pkg = getNextArgRequired();
                    final String mode = getNextArgRequired();
                    mService.shellMode(pkg, "own".equals(mode) ? 2 : "real".equals(mode) ? 1 : 0);
                    return 0;
                }
                case "layers": {
                    final int value = Integer.decode(getNextArgRequired());
                    final String pkg = getNextArg();
                    if (pkg == null) {
                        mService.shellLayers("", 0, value);
                    } else {
                        mService.shellLayers(pkg, Integer.decode(getNextArgRequired()), value);
                    }
                    return 0;
                }
                case "parse": {
                    final double[] p = mService.shellParse(getNextArgRequired());
                    pw.println(p == null ? "null" : p[0] + "," + p[1]);
                    return 0;
                }
                case "status":
                    pw.print(mService.shellDump());
                    return 0;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("bad number: " + e.getMessage());
            return 1;
        }
    }

    private String optionalTarget() {
        final String pkg = getNextArg();
        return pkg == null ? "" : pkg;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Samar location override. [pkg] targets one app's own profile.");
        pw.println("  on | off                  global override switch");
        pw.println("  set <lat> <lng> [pkg]     pin a point");
        pw.println("  goto <text> [pkg]         pin from coordinates, a map link or a plus code");
        pw.println("  joy <x> <y> [pkg]         joystick, each in [-1, 1]; 0 0 stops");
        pw.println("  speed <0-3> [pkg]         walk, run, bike, car");
        pw.println("  mode <pkg> follow|real|own");
        pw.println("  layers <bits> [pkg <mask>]  1 radio, 2 BLE, 4 photo location, 8 local network");
        pw.println("  parse <text>");
        pw.println("  status");
    }
}
