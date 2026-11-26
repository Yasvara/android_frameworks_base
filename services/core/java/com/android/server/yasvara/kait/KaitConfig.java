/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.android.server.yasvara.kait;

import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Persistent Kait configuration: enabled modules and per-user app scopes.
 *
 * <p>Stored atomically at {@code /data/system/yasvara_kait/config.json}. On first boot after
 * upgrading from the legacy Vector/Xposed runtime, automatically migrates
 * {@code /data/system/yasvara_xposed/config.json} so existing user module scopes are preserved.
 */
final class KaitConfig {
    private static final String TAG = "YasvaraKait";
    private static final File DIR = new File("/data/system/yasvara_kait");
    private static final File LEGACY_CONFIG = new File("/data/system/yasvara_xposed/config.json");

    static final class Module {
        boolean enabled;
        final ArraySet<String> scope = new ArraySet<>(); // "<userId>:<package>"
    }

    private final Object mLock = new Object();
    private final AtomicFile mFile;
    private final File mLegacyFile;
    private final ArrayMap<String, Module> mModules = new ArrayMap<>();

    KaitConfig() {
        this(new File(DIR, "config.json"), LEGACY_CONFIG);
    }

    KaitConfig(File configFile, File legacyFile) {
        final File parent = configFile.getParentFile();
        if (parent != null) parent.mkdirs();
        mFile = new AtomicFile(configFile);
        mLegacyFile = legacyFile;
        load();
    }

    private static String key(int userId, String pkg) {
        return userId + ":" + pkg;
    }

    /** Enabled modules scoped to {@code pkg} in {@code userId}, in stable sorted order. */
    List<String> modulesFor(String pkg, int userId) {
        final String k = key(userId, pkg);
        final List<String> out = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mModules.size(); i++) {
                Module m = mModules.valueAt(i);
                if (m.enabled && m.scope.contains(k)) out.add(mModules.keyAt(i));
            }
            // Self-injection: an enabled module always runs in its own process so libxposed UI
            // service delivery and legacy isXposedEnabled checks work seamlessly.
            final Module self = mModules.get(pkg);
            if (self != null && self.enabled && !out.contains(pkg)) out.add(pkg);
        }
        out.sort(null);
        return out;
    }

    boolean isScoped(String pkg, int userId) {
        final String k = key(userId, pkg);
        synchronized (mLock) {
            for (int i = 0; i < mModules.size(); i++) {
                Module m = mModules.valueAt(i);
                if (m.enabled && m.scope.contains(k)) return true;
            }
        }
        return false;
    }

    boolean isEnabled(String module) {
        synchronized (mLock) {
            Module m = mModules.get(module);
            return m != null && m.enabled;
        }
    }

    boolean anyEnabled() {
        synchronized (mLock) {
            for (int i = 0; i < mModules.size(); i++) {
                if (mModules.valueAt(i).enabled) return true;
            }
        }
        return false;
    }

    void setEnabled(String module, boolean enabled) {
        synchronized (mLock) {
            Module m = mModules.get(module);
            if (m == null) {
                if (!enabled) return;
                m = new Module();
                mModules.put(module, m);
            }
            m.enabled = enabled;
            save();
        }
    }

    List<String> getScope(String module, int userId) {
        final String prefix = userId + ":";
        final List<String> out = new ArrayList<>();
        synchronized (mLock) {
            Module m = mModules.get(module);
            if (m != null) {
                for (int i = 0; i < m.scope.size(); i++) {
                    String k = m.scope.valueAt(i);
                    if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
                }
            }
        }
        out.sort(null);
        return out;
    }

    /** Replaces the scope of {@code module} in {@code userId}; returns the packages that changed. */
    List<String> setScope(String module, int userId, List<String> packages) {
        final String prefix = userId + ":";
        final ArraySet<String> before = new ArraySet<>();
        final ArraySet<String> after = new ArraySet<>(packages);
        synchronized (mLock) {
            Module m = mModules.get(module);
            if (m == null) {
                m = new Module();
                mModules.put(module, m);
            }
            for (int i = m.scope.size() - 1; i >= 0; i--) {
                String k = m.scope.valueAt(i);
                if (k.startsWith(prefix)) {
                    before.add(k.substring(prefix.length()));
                    m.scope.removeAt(i);
                }
            }
            for (String p : after) m.scope.add(key(userId, p));
            save();
        }
        final List<String> changed = new ArrayList<>();
        for (String p : before) if (!after.contains(p)) changed.add(p);
        for (String p : after) if (!before.contains(p)) changed.add(p);
        return changed;
    }

    void forget(String module) {
        synchronized (mLock) {
            if (mModules.remove(module) != null) save();
        }
    }

    List<String> knownModules() {
        synchronized (mLock) {
            return new ArrayList<>(mModules.keySet());
        }
    }

    private void load() {
        synchronized (mLock) {
            if (readIntoMemory(mFile)) {
                return;
            }
            // One-time transparent migration from legacy yasvara_xposed config.
            if (mLegacyFile != null && mLegacyFile.isFile()) {
                if (readIntoMemory(new AtomicFile(mLegacyFile))) {
                    Slog.i(TAG, "Migrated " + mModules.size() + " module(s) from " + mLegacyFile);
                    save();
                }
            }
        }
    }

    private boolean readIntoMemory(AtomicFile source) {
        try {
            byte[] raw = source.readFully();
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONObject modules = root.optJSONObject("modules");
            if (modules == null) return true;
            for (Iterator<String> it = modules.keys(); it.hasNext(); ) {
                String name = it.next();
                JSONObject jm = modules.getJSONObject(name);
                Module m = new Module();
                m.enabled = jm.optBoolean("enabled");
                JSONArray scope = jm.optJSONArray("scope");
                if (scope != null) {
                    for (int i = 0; i < scope.length(); i++) m.scope.add(scope.getString(i));
                }
                mModules.put(name, m);
            }
            return true;
        } catch (FileNotFoundException e) {
            return false;
        } catch (IOException | JSONException e) {
            Slog.e(TAG, "Cannot read Kait config " + source.getBaseFile(), e);
            return false;
        }
    }

    private void save() {
        FileOutputStream out = null;
        try {
            JSONObject modules = new JSONObject();
            for (int i = 0; i < mModules.size(); i++) {
                Module m = mModules.valueAt(i);
                JSONObject jm = new JSONObject();
                jm.put("enabled", m.enabled);
                JSONArray scope = new JSONArray();
                for (int j = 0; j < m.scope.size(); j++) scope.put(m.scope.valueAt(j));
                jm.put("scope", scope);
                modules.put(mModules.keyAt(i), jm);
            }
            JSONObject root = new JSONObject().put("version", 2).put("modules", modules);
            out = mFile.startWrite();
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            mFile.finishWrite(out);
        } catch (IOException | JSONException e) {
            Slog.e(TAG, "Cannot write Kait config", e);
            if (out != null) mFile.failWrite(out);
        }
    }
}
