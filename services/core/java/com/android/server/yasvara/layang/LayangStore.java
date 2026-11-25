/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.layang;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Environment;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-user rules and remembered geometry of Layang windows, kept in
 * /data/system/yasvara_layang/&lt;userId&gt;.xml. Thread-safe; writes are debounced on the
 * service handler.
 */
final class LayangStore {
    private static final String TAG = "LayangStore";
    private static final int FILE_VERSION = 1;
    private static final long WRITE_DELAY_MS = 2000;

    /** Orientation classes of the remembered geometry. */
    static final int ORIENT_PORTRAIT = 0;
    static final int ORIENT_LANDSCAPE = 1;

    /** Remembered placement of one app in one orientation class. */
    static final class Geometry {
        /** Scale s of the virtual screen; 0 for a reflow window. */
        float scale;
        /** Window size in px; its shape is the virtual screen's aspect when scaled. */
        int width;
        int height;
        /** Window centre, normalised to the display (0..1). */
        float cx;
        float cy;

        Geometry copy() {
            final Geometry g = new Geometry();
            g.scale = scale;
            g.width = width;
            g.height = height;
            g.cx = cx;
            g.cy = cy;
            return g;
        }
    }

    /** Rules and geometry of one package. */
    static final class App {
        boolean always;
        boolean blocked;
        /** -1 default, 0 off, 1 on. */
        int readable = -1;
        /** -1 default, 0 unlocked, 1 locked. */
        int aspectLock = -1;
        /** "S", "M", "L" or null for the global default. */
        @Nullable String size;
        /** -1 auto (category/Pentas), 0 never a game, 1 always a game. */
        int game = -1;
        final Geometry[] geometry = new Geometry[2];

        boolean isEmpty() {
            return !always && !blocked && readable < 0 && aspectLock < 0 && size == null
                    && game < 0 && geometry[0] == null && geometry[1] == null;
        }

        boolean hasRules() {
            return always || blocked || readable >= 0 || aspectLock >= 0 || size != null
                    || game >= 0;
        }
    }

    private final Object mLock = new Object();
    private final Handler mHandler;
    private final File mDir;
    private final SparseArray<ArrayMap<String, App>> mUsers = new SparseArray<>();
    private final SparseArray<Runnable> mPendingWrites = new SparseArray<>();

    LayangStore(@NonNull Handler handler) {
        this(handler, new File(Environment.getDataSystemDirectory(), "yasvara_layang"));
    }

    LayangStore(@NonNull Handler handler, @NonNull File dir) {
        mHandler = handler;
        mDir = dir;
    }

    /** Copy of the app's entry, or null when it has none. */
    @Nullable
    App get(int userId, @NonNull String pkg) {
        synchronized (mLock) {
            final App a = userLocked(userId).get(pkg);
            return a != null ? copy(a) : null;
        }
    }

    /** Edits the app's entry under the lock and schedules a write. */
    void edit(int userId, @NonNull String pkg, @NonNull java.util.function.Consumer<App> editor) {
        synchronized (mLock) {
            final ArrayMap<String, App> apps = userLocked(userId);
            App a = apps.get(pkg);
            if (a == null) {
                a = new App();
                apps.put(pkg, a);
            }
            editor.accept(a);
            if (a.isEmpty()) {
                apps.remove(pkg);
            }
            scheduleWriteLocked(userId);
        }
    }

    @NonNull
    List<String> packagesWithRules(int userId) {
        synchronized (mLock) {
            final ArrayMap<String, App> apps = userLocked(userId);
            final ArrayList<String> out = new ArrayList<>();
            for (int i = 0; i < apps.size(); i++) {
                if (apps.valueAt(i).hasRules()) {
                    out.add(apps.keyAt(i));
                }
            }
            return out;
        }
    }

    /** Packages forced to be games (1) or never games (0) by a rule. */
    @NonNull
    ArrayMap<String, Integer> gameRules(int userId) {
        synchronized (mLock) {
            final ArrayMap<String, App> apps = userLocked(userId);
            final ArrayMap<String, Integer> out = new ArrayMap<>();
            for (int i = 0; i < apps.size(); i++) {
                if (apps.valueAt(i).game >= 0) {
                    out.put(apps.keyAt(i), apps.valueAt(i).game);
                }
            }
            return out;
        }
    }

    void onUserRemoved(int userId) {
        synchronized (mLock) {
            mUsers.remove(userId);
            final Runnable r = mPendingWrites.get(userId);
            if (r != null) {
                mHandler.removeCallbacks(r);
                mPendingWrites.remove(userId);
            }
        }
        new AtomicFile(fileFor(userId)).delete();
    }

    void dump(@NonNull PrintWriter pw, int userId) {
        synchronized (mLock) {
            final ArrayMap<String, App> apps = userLocked(userId);
            pw.println("  store u" + userId + " (" + apps.size() + " apps)");
            for (int i = 0; i < apps.size(); i++) {
                final App a = apps.valueAt(i);
                final StringBuilder sb = new StringBuilder("    ").append(apps.keyAt(i));
                if (a.always) sb.append(" always");
                if (a.blocked) sb.append(" blocked");
                if (a.readable >= 0) sb.append(" reflow=").append(a.readable);
                if (a.aspectLock >= 0) sb.append(" aspectLock=").append(a.aspectLock);
                if (a.size != null) sb.append(" size=").append(a.size);
                if (a.game >= 0) sb.append(" game=").append(a.game);
                for (int o = 0; o < 2; o++) {
                    final Geometry g = a.geometry[o];
                    if (g != null) {
                        sb.append(o == ORIENT_PORTRAIT ? " port[" : " land[");
                        if (g.scale > 0) sb.append("s=").append(g.scale).append(' ');
                        if (g.width > 0) sb.append(g.width).append('x').append(g.height).append(' ');
                        sb.append(String.format("c=%.3f,%.3f]", g.cx, g.cy));
                    }
                }
                pw.println(sb);
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private ArrayMap<String, App> userLocked(int userId) {
        ArrayMap<String, App> apps = mUsers.get(userId);
        if (apps == null) {
            apps = new ArrayMap<>();
            mUsers.put(userId, apps);
            readLocked(userId, apps);
        }
        return apps;
    }

    private static App copy(App a) {
        final App c = new App();
        c.always = a.always;
        c.blocked = a.blocked;
        c.readable = a.readable;
        c.aspectLock = a.aspectLock;
        c.size = a.size;
        c.game = a.game;
        for (int o = 0; o < 2; o++) {
            c.geometry[o] = a.geometry[o] != null ? a.geometry[o].copy() : null;
        }
        return c;
    }

    private File fileFor(int userId) {
        return new File(mDir, userId + ".xml");
    }

    private void scheduleWriteLocked(int userId) {
        Runnable r = mPendingWrites.get(userId);
        if (r == null) {
            r = () -> writeNow(userId);
            mPendingWrites.put(userId, r);
        }
        mHandler.removeCallbacks(r);
        mHandler.postDelayed(r, WRITE_DELAY_MS);
    }

    private void writeNow(int userId) {
        final AtomicFile file = new AtomicFile(fileFor(userId));
        FileOutputStream out = null;
        try {
            mDir.mkdirs();
            out = file.startWrite();
            final TypedXmlSerializer xml = Xml.resolveSerializer(out);
            xml.startDocument(null, true);
            xml.startTag(null, "layang");
            xml.attributeInt(null, "version", FILE_VERSION);
            synchronized (mLock) {
                final ArrayMap<String, App> apps = userLocked(userId);
                for (int i = 0; i < apps.size(); i++) {
                    writeApp(xml, apps.keyAt(i), apps.valueAt(i));
                }
            }
            xml.endTag(null, "layang");
            xml.endDocument();
            file.finishWrite(out);
        } catch (Throwable t) {
            Slog.e(TAG, "write failed for u" + userId, t);
            if (out != null) file.failWrite(out);
        }
    }

    private static void writeApp(TypedXmlSerializer xml, String pkg, App a)
            throws java.io.IOException {
        xml.startTag(null, "app");
        xml.attribute(null, "pkg", pkg);
        if (a.always) xml.attributeBoolean(null, "always", true);
        if (a.blocked) xml.attributeBoolean(null, "blocked", true);
        if (a.readable >= 0) xml.attributeInt(null, "readable", a.readable);
        if (a.aspectLock >= 0) xml.attributeInt(null, "aspectLock", a.aspectLock);
        if (a.size != null) xml.attribute(null, "size", a.size);
        if (a.game >= 0) xml.attributeInt(null, "game", a.game);
        for (int o = 0; o < 2; o++) {
            final Geometry g = a.geometry[o];
            if (g == null) continue;
            xml.startTag(null, "geometry");
            xml.attributeInt(null, "orient", o);
            if (g.scale > 0) xml.attributeFloat(null, "scale", g.scale);
            if (g.width > 0) {
                xml.attributeInt(null, "w", g.width);
                xml.attributeInt(null, "h", g.height);
            }
            xml.attributeFloat(null, "cx", g.cx);
            xml.attributeFloat(null, "cy", g.cy);
            xml.endTag(null, "geometry");
        }
        xml.endTag(null, "app");
    }

    private void readLocked(int userId, ArrayMap<String, App> apps) {
        final AtomicFile file = new AtomicFile(fileFor(userId));
        if (!file.exists()) return;
        try (FileInputStream in = file.openRead()) {
            final TypedXmlPullParser xml = Xml.resolvePullParser(in);
            App app = null;
            int type;
            while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.END_TAG && "app".equals(xml.getName())) {
                    app = null;
                    continue;
                }
                if (type != XmlPullParser.START_TAG) continue;
                switch (xml.getName()) {
                    case "app": {
                        final String pkg = xml.getAttributeValue(null, "pkg");
                        if (pkg == null) break;
                        app = new App();
                        app.always = xml.getAttributeBoolean(null, "always", false);
                        app.blocked = xml.getAttributeBoolean(null, "blocked", false);
                        app.readable = xml.getAttributeInt(null, "readable", -1);
                        app.aspectLock = xml.getAttributeInt(null, "aspectLock", -1);
                        app.size = xml.getAttributeValue(null, "size");
                        app.game = xml.getAttributeInt(null, "game", -1);
                        apps.put(pkg, app);
                        break;
                    }
                    case "geometry": {
                        if (app == null) break;
                        final int o = xml.getAttributeInt(null, "orient", -1);
                        if (o != ORIENT_PORTRAIT && o != ORIENT_LANDSCAPE) break;
                        final Geometry g = new Geometry();
                        g.scale = xml.getAttributeFloat(null, "scale", 0f);
                        g.width = xml.getAttributeInt(null, "w", 0);
                        g.height = xml.getAttributeInt(null, "h", 0);
                        g.cx = xml.getAttributeFloat(null, "cx", 0.5f);
                        g.cy = xml.getAttributeFloat(null, "cy", 0.5f);
                        app.geometry[o] = g;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            // A damaged file only loses remembered positions and rules.
            Slog.e(TAG, "read failed for u" + userId + ", starting empty", t);
            apps.clear();
        }
    }
}
