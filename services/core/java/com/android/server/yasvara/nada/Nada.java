/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.nada;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ServiceManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client side of Nada. The engine defines every setting; a settings page reads the
 * {@link Schema} and draws it, so a new setting needs no change here or in any page.
 *
 * @hide
 */
public final class Nada {
    private Nada() {}

    public static final String SERVICE_NAME = "yasvara_nada";
    public static final String[] ROUTES = {"spk", "wired", "usb", "bt", "other"};
    public static final String SCOPE_APP = "app:";
    /** Keys the service applies to the Dolby effect itself; the engine never sees them. */
    public static final String DOLBY = "dolby.";
    /** The engine's master switch. */
    public static final String MASTER = "on";

    @Nullable
    public static INadaManager get() {
        return INadaManager.Stub.asInterface(ServiceManager.getService(SERVICE_NAME));
    }

    public static final class Param {
        public static final int BOOL = 0;
        public static final int NUM = 1;
        public static final int BANDS = 2;
        public static final int TEXT = 3;
        /** A choice; {@link #options} holds the labels, the value is the index. */
        public static final int ENUM = 4;

        public String key;
        public String group;
        public int type;
        public float min;
        public float max;
        public float step;
        public float def;
        public String unit;
        public String title;
        public String[] options = new String[0];
    }

    public static final class Group {
        public String id;
        public String title;
        /** master, tone, dynamics, colour, spatial, output or dsp. */
        public String kind = "";
        /** One line on what the effect does to the sound. */
        public String summary = "";
        public final List<Param> params = new ArrayList<>();

        /** The effect's own switch ({@code <id>.on}), or null when it has none. */
        @Nullable
        public Param switchParam() {
            for (Param p : params) {
                if (p.type == Param.BOOL && p.key.equals(id + ".on")) return p;
            }
            return null;
        }
    }

    /** The engine's settings table: groups in display order, each with its settings. */
    public static final class Schema {
        public int version;
        public final List<Group> groups = new ArrayList<>();
        public final Map<String, Param> params = new LinkedHashMap<>();

        @NonNull
        public static Schema parse(@Nullable String text) {
            final Schema s = new Schema();
            if (text == null) return s;
            for (String line : text.split("\n")) {
                final String[] f = line.split("\t", -1);
                try {
                    if (f[0].equals("v") && f.length >= 2) {
                        s.version = Integer.parseInt(f[1]);
                    } else if (f[0].equals("g") && f.length >= 3) {
                        final Group g = new Group();
                        g.id = f[1];
                        g.title = f[2];
                        if (f.length >= 5) {
                            g.kind = f[3];
                            g.summary = f[4];
                        }
                        s.groups.add(g);
                    } else if (f[0].equals("p") && f.length >= 10) {
                        final Param p = new Param();
                        p.key = f[1];
                        p.group = f[2];
                        p.type = switch (f[3]) {
                            case "bool" -> Param.BOOL;
                            case "num" -> Param.NUM;
                            case "bands" -> Param.BANDS;
                            case "enum" -> Param.ENUM;
                            default -> Param.TEXT;
                        };
                        p.min = Float.parseFloat(f[4]);
                        p.max = Float.parseFloat(f[5]);
                        p.step = Float.parseFloat(f[6]);
                        p.def = Float.parseFloat(f[7]);
                        p.unit = f[8];
                        p.title = f[9];
                        if (p.type == Param.ENUM) {
                            p.options = p.unit.split(",");
                            p.unit = "";
                        }
                        s.params.put(p.key, p);
                        for (Group g : s.groups) {
                            if (g.id.equals(p.group)) g.params.add(p);
                        }
                    }
                } catch (NumberFormatException e) {
                    // A row this build cannot read is skipped; the rest still draws.
                }
            }
            return s;
        }
    }

    /** "key:value|key:value" to a map, in order. */
    @NonNull
    public static Map<String, String> decode(@Nullable String spec) {
        final Map<String, String> m = new LinkedHashMap<>();
        if (spec == null) return m;
        for (String tok : spec.split("\\|")) {
            final int i = tok.indexOf(':');
            if (i > 0) m.put(tok.substring(0, i), tok.substring(i + 1));
        }
        return m;
    }

    @NonNull
    public static String encode(@NonNull Map<String, String> values) {
        final StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (b.length() > 0) b.append('|');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        return b.toString();
    }
}
