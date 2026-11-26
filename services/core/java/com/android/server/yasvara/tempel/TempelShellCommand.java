/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.tempel;

import android.os.ShellCommand;

import java.io.PrintWriter;

/** Shell interface: `cmd yasvara_tempel <status|list|arm|disarm|shield|add-uid|add-payment|remove|caps>`. */
final class TempelShellCommand extends ShellCommand {
    private final YasvaraTempelService mService;

    TempelShellCommand(YasvaraTempelService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) return handleDefaultCommands(null);
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "status":
                case "list":
                    pw.print(mService.dumpText());
                    return 0;
                case "caps":
                    pw.println(mService.getCapabilitiesSummary());
                    return 0;
                case "arm": {
                    final int id = Integer.parseInt(getNextArgRequired());
                    final boolean ok = mService.activateProfile(id);
                    pw.println("arm id=" + id + " ok=" + ok);
                    return ok ? 0 : 1;
                }
                case "disarm": {
                    final boolean ok = mService.activateProfile(0);
                    pw.println("disarm ok=" + ok);
                    return ok ? 0 : 1;
                }
                case "shield": {
                    final String arg = getNextArg();
                    final boolean on = arg == null ? !mService.isShieldEnabled()
                            : ("1".equals(arg) || "on".equalsIgnoreCase(arg) || "true".equalsIgnoreCase(arg));
                    final boolean ok = mService.setShieldEnabled(on);
                    pw.println("shield=" + on + " ok=" + ok);
                    return ok ? 0 : 1;
                }
                case "add-uid": {
                    final String name = getNextArgRequired();
                    final byte[] uid = unhex(getNextArgRequired());
                    final int sak = parseHexOpt(getNextArg(), 0x08);
                    final byte[] atqa = unhexOpt(getNextArg(), new byte[]{0x04, 0x00});
                    final int id = mService.upsertProfile(
                            0, TempelNative.KIND_UID_TYPE_A, name, 0xFF3B82F6, 2,
                            1, 2, 60, true, "", uid, atqa, sak, 0,
                            new byte[0], new byte[0], new byte[0], new byte[0], new byte[0]);
                    pw.println("added id=" + id);
                    return id > 0 ? 0 : 1;
                }
                case "add-payment": {
                    final String name = getNextArgRequired();
                    final String pkg = getNextArgRequired();
                    final int id = mService.upsertProfile(
                            0, TempelNative.KIND_PAYMENT_WALLET, name, 0xFF00AA5B, 1,
                            2, 0, 0, true, pkg, new byte[0], new byte[]{0x04, 0x00}, 0x20, 0,
                            new byte[0], new byte[0], new byte[0], new byte[0], new byte[0]);
                    pw.println("added payment id=" + id);
                    return id > 0 ? 0 : 1;
                }
                case "remove": {
                    final int id = Integer.parseInt(getNextArgRequired());
                    final boolean ok = mService.removeProfile(id);
                    pw.println("removed id=" + id + " ok=" + ok);
                    return ok ? 0 : 1;
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            getErrPrintWriter().println("error: " + e.getMessage());
            return 1;
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Tempel NFC controller (cmd yasvara_tempel):");
        pw.println("  status | list");
        pw.println("  caps");
        pw.println("  arm <profileId>");
        pw.println("  disarm");
        pw.println("  shield [on|off]");
        pw.println("  add-uid <name> <uidHex> [sakHex=08] [atqaHex=0400]");
        pw.println("  add-payment <name> <packageName>");
        pw.println("  remove <profileId>");
    }

    private static int parseHexOpt(String s, int def) {
        return (s == null || s.isEmpty()) ? def : Integer.parseInt(s, 16);
    }

    private static byte[] unhexOpt(String s, byte[] def) {
        return (s == null || s.isEmpty()) ? def : unhex(s);
    }

    private static byte[] unhex(String s) {
        final String h = s.replace(":", "").replace(" ", "");
        if ((h.length() & 1) != 0) throw new IllegalArgumentException("odd hex: " + s);
        final byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
