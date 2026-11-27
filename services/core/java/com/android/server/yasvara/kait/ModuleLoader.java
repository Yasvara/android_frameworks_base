/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.android.server.yasvara.kait;

import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Slog;

import org.yasvara.kait.ipc.ModuleCode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads module APKs into {@link ModuleCode} with zero-copy {@link SharedMemory} DEX streaming
 * and {@code (length, mtime)} caching for both full loads and {@link #looksLikeModule} probes.
 */
public final class ModuleLoader {
    private static final String TAG = "YasvaraKait";
    private static final int MAX_PROBE_CACHE = 512;

    static final class Result {
        final ModuleCode code; // null when not loadable
        final String reason;
        final long apkMtime;

        Result(ModuleCode code, String reason, long apkMtime) {
            this.code = code;
            this.reason = reason;
            this.apkMtime = apkMtime;
        }
    }

    private static final class Entry {
        final long length;
        final long mtime;
        final Result result;

        Entry(long length, long mtime, Result result) {
            this.length = length;
            this.mtime = mtime;
            this.result = result;
        }
    }

    private static final class ProbeEntry {
        final long length;
        final long mtime;
        final boolean isModule;

        ProbeEntry(long length, long mtime, boolean isModule) {
            this.length = length;
            this.mtime = mtime;
            this.isModule = isModule;
        }
    }

    private static final ArrayMap<String, ProbeEntry> sProbeCache = new ArrayMap<>();

    private final ArrayMap<String, Entry> mCache = new ArrayMap<>();

    synchronized void invalidate(String apkPath) {
        if (apkPath == null) return;
        mCache.remove(apkPath);
        synchronized (sProbeCache) {
            sProbeCache.remove(apkPath);
        }
    }

    synchronized void pruneMissing() {
        for (int i = mCache.size() - 1; i >= 0; i--) {
            if (!new File(mCache.keyAt(i)).isFile()) {
                mCache.removeAt(i);
            }
        }
        synchronized (sProbeCache) {
            for (int i = sProbeCache.size() - 1; i >= 0; i--) {
                if (!new File(sProbeCache.keyAt(i)).isFile()) {
                    sProbeCache.removeAt(i);
                }
            }
        }
    }

    synchronized Result load(String apkPath) {
        final File file = new File(apkPath);
        final long len = file.length();
        final long mtime = file.lastModified();
        final Entry cached = mCache.get(apkPath);
        if (cached != null && cached.length == len && cached.mtime == mtime) {
            return cached.result;
        }
        final Result result = loadUncached(file, mtime);
        mCache.put(apkPath, new Entry(len, mtime, result));
        return result;
    }

    /**
     * Cheap cached check used by Kait manager and Tabir ({@code YasvaraAppVisibilityService}):
     * returns true if the APK contains legacy or modern Xposed entry lists.
     */
    public static boolean looksLikeModule(String apkPath) {
        if (apkPath == null) return false;
        final File file = new File(apkPath);
        final long len = file.length();
        final long mtime = file.lastModified();
        if (len <= 0) return false;
        synchronized (sProbeCache) {
            final ProbeEntry cached = sProbeCache.get(apkPath);
            if (cached != null && cached.length == len && cached.mtime == mtime) {
                return cached.isModule;
            }
        }
        boolean found = false;
        try (ZipFile zip = new ZipFile(file)) {
            found = zip.getEntry("assets/xposed_init") != null
                    || zip.getEntry("META-INF/xposed/java_init.list") != null;
        } catch (IOException ignored) {
            found = false;
        }
        synchronized (sProbeCache) {
            if (sProbeCache.size() >= MAX_PROBE_CACHE) {
                sProbeCache.clear();
            }
            sProbeCache.put(apkPath, new ProbeEntry(len, mtime, found));
        }
        return found;
    }

    private static Result loadUncached(File file, long mtime) {
        if (!file.exists()) return new Result(null, "missing apk", mtime);
        final ModuleCode code = new ModuleCode();
        final List<SharedMemory> dexes = new ArrayList<>();
        final List<String> classNames = new ArrayList<>();
        final List<String> libraryNames = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            final Properties props = new Properties();
            final ZipEntry propEntry = zip.getEntry("META-INF/xposed/module.prop");
            if (propEntry != null) {
                try (InputStream in = zip.getInputStream(propEntry)) {
                    props.load(in);
                } catch (IllegalArgumentException | IOException e) {
                    Slog.w(TAG, "Malformed module.prop in " + file, e);
                }
            }
            final int targetApi = leadingInt(props.getProperty("targetApiVersion"));
            final boolean hasLegacy = zip.getEntry("assets/xposed_init") != null;
            final boolean legacy;
            if (targetApi >= 101) {
                legacy = false;
                readList(zip, "META-INF/xposed/java_init.list", classNames);
                readList(zip, "META-INF/xposed/native_init.list", libraryNames);
            } else if (hasLegacy) {
                legacy = true;
                readList(zip, "assets/xposed_init", classNames);
                readList(zip, "assets/native_init", libraryNames);
            } else if (targetApi == 100) {
                return new Result(null, "libxposed API 100 is no longer supported", mtime);
            } else {
                return new Result(null, "no xposed entry list", mtime);
            }
            if (classNames.isEmpty()) return new Result(null, "no module classes", mtime);

            for (int i = 1; ; i++) {
                final ZipEntry dex = zip.getEntry(i == 1 ? "classes.dex" : "classes" + i + ".dex");
                if (dex == null) break;
                try (InputStream in = zip.getInputStream(dex)) {
                    dexes.add(readDexZeroCopy(in, dex.getSize()));
                }
            }
            if (dexes.isEmpty()) return new Result(null, "no dex", mtime);

            code.preLoadedDexes = dexes;
            code.moduleClassNames = classNames;
            code.moduleLibraryNames = libraryNames;
            code.legacy = legacy;
            code.targetApiVersion = targetApi;
            code.autoHotReload = props.getProperty("autoHotReload", "").trim().equalsIgnoreCase("true");
            code.exceptionPassthrough =
                    props.getProperty("exceptionMode", "").trim().equalsIgnoreCase("passthrough");
            return new Result(code, null, mtime);
        } catch (IOException | ErrnoException e) {
            Slog.e(TAG, "Failed to load module " + file, e);
            return new Result(null, "unreadable apk: " + e.getMessage(), mtime);
        }
    }

    /**
     * Streams DEX bytes directly into a mapped {@link SharedMemory} buffer when the uncompressed
     * zip entry size is known, avoiding a duplicate multi-megabyte heap byte array.
     */
    private static SharedMemory readDexZeroCopy(InputStream in, long declaredSize)
            throws IOException, ErrnoException {
        if (declaredSize > 0 && declaredSize <= Integer.MAX_VALUE) {
            final int size = (int) declaredSize;
            final SharedMemory memory = SharedMemory.create(null, size);
            final ByteBuffer buffer = memory.mapReadWrite();
            try {
                final ReadableByteChannel ch = Channels.newChannel(in);
                while (buffer.hasRemaining()) {
                    if (ch.read(buffer) < 0) break;
                }
            } finally {
                SharedMemory.unmap(buffer);
            }
            memory.setProtect(OsConstants.PROT_READ);
            return memory;
        }
        // Fallback when zip entry size is unknown (-1).
        final byte[] all = in.readAllBytes();
        final SharedMemory memory = SharedMemory.create(null, all.length);
        final ByteBuffer buffer = memory.mapReadWrite();
        try {
            buffer.put(all);
        } finally {
            SharedMemory.unmap(buffer);
        }
        memory.setProtect(OsConstants.PROT_READ);
        return memory;
    }

    private static void readList(ZipFile zip, String name, List<String> dest) throws IOException {
        final ZipEntry entry = zip.getEntry(name);
        if (entry == null) return;
        try (InputStream in = zip.getInputStream(entry);
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) dest.add(line);
            }
        }
    }

    private static int leadingInt(String value) {
        if (value == null) return 0;
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') break;
            result = result * 10 + (c - '0');
        }
        return result;
    }
}
