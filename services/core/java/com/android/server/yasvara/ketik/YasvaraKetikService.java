/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.ketik;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets Gboard's flags through its own app restrictions. Gboard copies them by name into a flag
 * source ranked above server rollouts, so the stock APK keeps its signature and Play updates.
 *
 * <p>The store here is the truth; the DevicePolicy entry follows it at boot and on reinstall. A
 * broken keyboard can block the PIN pad, so a crash soon after a change undoes that change.
 */
public final class YasvaraKetikService extends IYasvaraKetik.Stub {
    private static final String TAG = "YasvaraKetik";

    public static final String SERVICE_NAME = "yasvara_ketik";
    static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_KETIK";
    private static final String PKG = "com.google.android.inputmethod.latin";
    private static final ComponentName IME =
            new ComponentName(PKG, "com.android.inputmethod.latin.LatinIME");
    private static final String ENTITY = "yasvara.ketik";
    private static final String LARAS = "org.yasvara.laras";
    private static final String ACTION_REVERTED = "org.yasvara.laras.action.KETIK_REVERTED";

    private static final File DIR = new File("/data/system/yasvara");
    private static final int MAX_TASKS = 64;
    private static final int MAX_KEYS = 256;
    private static final int MAX_VALUE_CHARS = 4096;
    /** Forcing these could put typed text in logs or change what is uploaded. */
    private static final Pattern DENIED = Pattern.compile(
            "(^|_)(debug|logcat|log|logging|metrics|dry_run|training|federated|clearcut|primes)(_|$)");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_.]{0,127}");
    private static final Pattern FLAG_LINE = Pattern.compile(
            "^\\s*([a-z][a-zA-Z0-9_.]*)\\{type=(\\w+), finalValue=(.*?)"
                    + "(?:, ((?:DEFAULT|SYSTEM_SETTING|PHENOTYPE|MANAGED_CONFIG|OEM|OVERRIDE"
                    + "|HERMETRIC_OVERRIDE)=.*))?\\}\\s*$");
    private static final Pattern SOURCE = Pattern.compile("(?:^|, )([A-Z_]+)=");
    private static final long DUMP_TIMEOUT_MS = 8000;

    private static final int CRASH_STRIKES = 3;
    private static final long CRASH_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);

    private static volatile YasvaraKetikService sInstance;

    private final Context mContext;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<UserState> mUsers = new SparseArray<>();

    private static final class UserState {
        Bundle current = new Bundle();
        /** What a crash goes back to; null when there is nothing left to undo but clearing. */
        Bundle previous;
        long changedAt;
        final ArrayDeque<Long> strikes = new ArrayDeque<>();
        List<String> lastRevert = new ArrayList<>();
    }

    public YasvaraKetikService(Context context) {
        mContext = context;
        sInstance = this;

        IntentFilter boot = new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                syncPolicy(getSendingUserId());
            }
        }, boot, null, null);

        IntentFilter pkg = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkg.addDataScheme("package");
        pkg.addDataSchemeSpecificPart(PKG, android.os.PatternMatcher.PATTERN_LITERAL);
        context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                syncPolicy(getSendingUserId());
            }
        }, pkg, null, null);
    }

    @Override
    public Bundle getOverrides() {
        enforce();
        synchronized (mLock) {
            return new Bundle(stateLocked(UserHandle.getCallingUserId()).current);
        }
    }

    @Override
    public void setOverrides(Bundle values) {
        enforce();
        final Bundle clean = validate(values == null ? new Bundle() : values);
        final int user = UserHandle.getCallingUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                UserState s = stateLocked(user);
                s.previous = new Bundle(s.current);
                s.current = clean;
                s.changedAt = SystemClock.elapsedRealtime();
                s.strikes.clear();
                writeLocked(user, s);
            }
            applyPolicy(user, clean);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> readFlagStates(String[] names) {
        enforce();
        final Set<String> wanted = names == null ? null : new TreeSet<>(Arrays.asList(names));
        final long token = Binder.clearCallingIdentity();
        try {
            return readDump(wanted);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> getLastRevert() {
        enforce();
        synchronized (mLock) {
            return new ArrayList<>(stateLocked(UserHandle.getCallingUserId()).lastRevert);
        }
    }

    @Override
    public void clearLastRevert() {
        enforce();
        final int user = UserHandle.getCallingUserId();
        synchronized (mLock) {
            UserState s = stateLocked(user);
            s.lastRevert = new ArrayList<>();
            writeLocked(user, s);
        }
    }

    /** AppErrors: a process of pkg crashed. Never blocks the crash path. */
    public static void noteCrash(String pkg, int userId) {
        final YasvaraKetikService svc = sInstance;
        if (svc == null || !PKG.equals(pkg)) return;
        com.android.internal.os.BackgroundThread.getHandler().post(() -> svc.onCrash(userId));
    }

    private void onCrash(int user) {
        final Bundle next;
        final List<String> removed;
        synchronized (mLock) {
            UserState s = stateLocked(user);
            final long now = SystemClock.elapsedRealtime();
            if (s.current.isEmpty() || now - s.changedAt > CRASH_WINDOW_MS) return;
            s.strikes.addLast(now);
            if (s.strikes.size() < CRASH_STRIKES) return;

            // The first revert undoes the last change. Crashing on after that clears everything.
            next = s.previous != null ? s.previous : new Bundle();
            removed = diffKeys(s.current, next);
            s.previous = null;
            s.current = next;
            s.changedAt = now;
            s.strikes.clear();
            s.lastRevert = removed;
            writeLocked(user, s);
        }
        Slog.w(TAG, "Gboard kept crashing after a flag change; removed " + removed);
        applyPolicy(user, next);
        Intent intent = new Intent(ACTION_REVERTED).setPackage(LARAS)
                .putStringArrayListExtra("flags", new ArrayList<>(removed));
        mContext.sendBroadcastAsUser(intent, UserHandle.of(user), PERMISSION_MANAGE);
    }

    private static List<String> diffKeys(Bundle from, Bundle to) {
        final ArrayList<String> out = new ArrayList<>();
        for (String k : new TreeSet<>(from.keySet())) {
            if (!to.containsKey(k) || !Objects.equals(from.get(k), to.get(k))) out.add(k);
        }
        return out;
    }

    private void syncPolicy(int user) {
        final Bundle want;
        synchronized (mLock) {
            want = new Bundle(stateLocked(user).current);
        }
        final UserManager um = mContext.getSystemService(UserManager.class);
        final Bundle have = um.getApplicationRestrictions(PKG, UserHandle.of(user));
        if (sameContent(want, have)) return;
        applyPolicy(user, want);
    }

    private static boolean sameContent(Bundle a, Bundle b) {
        if (a.size() != b.size() || !a.keySet().equals(b.keySet())) return false;
        for (String k : a.keySet()) {
            final Object va = a.get(k);
            final Object vb = b.get(k);
            if (!Objects.equals(va, vb)) return false;
        }
        return true;
    }

    private void applyPolicy(int user, Bundle values) {
        closeSettingsInBackground(user);
        try {
            mContext.createContextAsUser(UserHandle.of(user), 0)
                    .getSystemService(DevicePolicyManager.class)
                    .setApplicationRestrictionsBySystem(ENTITY, PKG,
                            values.isEmpty() ? null : new Bundle(values));
        } catch (RuntimeException e) {
            Slog.e(TAG, "Could not apply overrides for user " + user, e);
        }
    }

    /**
     * Gboard's settings restart themselves on any restriction change, which would bring a task
     * left in the background over whatever the owner is using.
     */
    private static void closeSettingsInBackground(int user) {
        try {
            final ActivityTaskManager atm = ActivityTaskManager.getInstance();
            for (ActivityManager.RunningTaskInfo t : atm.getTasks(MAX_TASKS)) {
                if (t.userId == user && !t.isVisible && t.baseActivity != null
                        && PKG.equals(t.baseActivity.getPackageName())) {
                    atm.removeTask(t.taskId);
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Could not close Gboard's settings: " + e);
        }
    }

    private static Bundle validate(Bundle in) {
        if (in.size() > MAX_KEYS) throw new IllegalArgumentException("Too many flags");
        final Bundle out = new Bundle();
        for (String key : in.keySet()) {
            final Object v = in.get(key);
            if (!KEY.matcher(key).matches() || DENIED.matcher(key).find() || !isSimple(v)) {
                throw new IllegalArgumentException("Flag not allowed: " + key);
            }
            putSimple(out, key, v);
        }
        return out;
    }

    /** The only types that survive the DevicePolicy store and Gboard's copy into its flags. */
    private static boolean isSimple(Object v) {
        return v instanceof Boolean || v instanceof Integer
                || (v instanceof String && ((String) v).length() <= MAX_VALUE_CHARS);
    }

    private static void putSimple(Bundle b, String k, Object v) {
        if (v instanceof Boolean) b.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) b.putInt(k, (Integer) v);
        else b.putString(k, (String) v);
    }

    /** The dump also holds keyboard history: only flag lines leave the reader, and nothing stays. */
    private List<String> readDump(Set<String> wanted) {
        final ArrayList<String> out = new ArrayList<>();
        final IBinder am = ServiceManager.getService(Context.ACTIVITY_SERVICE);
        if (am == null) return out;
        ParcelFileDescriptor read = null;
        ParcelFileDescriptor write = null;
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            read = pipe[0];
            write = pipe[1];
            final ParcelFileDescriptor source = read;
            final Thread reader = new Thread(() -> parse(source, wanted, out), TAG + "Dump");
            reader.start();
            am.dump(write.getFileDescriptor(), new String[] {"service", IME.flattenToString()});
            write.close();
            write = null;
            reader.join(DUMP_TIMEOUT_MS);
            if (reader.isAlive()) {
                reader.interrupt();
                return new ArrayList<>();
            }
        } catch (IOException | android.os.RemoteException | InterruptedException e) {
            Slog.w(TAG, "Could not read Gboard's flags: " + e);
        } finally {
            closeQuietly(write);
            closeQuietly(read);
        }
        synchronized (out) {
            return new ArrayList<>(out);
        }
    }

    private static void parse(ParcelFileDescriptor fd, Set<String> wanted, List<String> out) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(fd.getFileDescriptor()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.indexOf("{type=") < 0) continue;
                final Matcher m = FLAG_LINE.matcher(line);
                if (!m.matches() || (wanted != null && !wanted.contains(m.group(1)))) continue;
                final ArrayList<String> sources = new ArrayList<>();
                if (m.group(4) != null) {
                    final Matcher s = SOURCE.matcher(m.group(4));
                    while (s.find()) sources.add(s.group(1));
                }
                final String row = m.group(1) + '\t' + m.group(2) + '\t' + m.group(3) + '\t'
                        + String.join(",", sources);
                synchronized (out) {
                    out.add(row);
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, "Reading Gboard's flags stopped: " + e);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) return;
        try {
            fd.close();
        } catch (IOException ignored) {
        }
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE, TAG);
    }

    @GuardedBy("mLock")
    private UserState stateLocked(int user) {
        UserState s = mUsers.get(user);
        if (s == null) {
            s = new UserState();
            readLocked(user, s);
            mUsers.put(user, s);
        }
        return s;
    }

    private static AtomicFile file(int user) {
        return new AtomicFile(new File(DIR, "ketik_" + user + ".xml"));
    }

    @GuardedBy("mLock")
    private void writeLocked(int user, UserState s) {
        final AtomicFile file = file(user);
        FileOutputStream os = null;
        try {
            DIR.mkdirs();
            os = file.startWrite();
            TypedXmlSerializer x = Xml.resolveSerializer(os);
            x.startDocument(null, true);
            x.startTag(null, "ketik");
            writeValues(x, s.current);
            for (String name : s.lastRevert) {
                x.startTag(null, "reverted");
                x.attribute(null, "n", name);
                x.endTag(null, "reverted");
            }
            x.endTag(null, "ketik");
            x.endDocument();
            file.finishWrite(os);
        } catch (IOException e) {
            Slog.e(TAG, "Could not save overrides", e);
            if (os != null) file.failWrite(os);
        }
    }

    private static void writeValues(TypedXmlSerializer x, Bundle b) throws IOException {
        for (String k : new TreeSet<>(b.keySet())) {
            final Object v = b.get(k);
            x.startTag(null, "flag");
            x.attribute(null, "n", k);
            x.attribute(null, "t", v instanceof Boolean ? "b" : v instanceof Integer ? "i" : "s");
            x.attribute(null, "v", String.valueOf(v));
            x.endTag(null, "flag");
        }
    }

    @GuardedBy("mLock")
    private void readLocked(int user, UserState s) {
        final AtomicFile file = file(user);
        if (!file.exists()) return;
        try (FileInputStream in = file.openRead()) {
            TypedXmlPullParser p = Xml.resolvePullParser(in);
            final Bundle flags = new Bundle();
            int type;
            while ((type = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) continue;
                switch (p.getName()) {
                    case "flag" -> readValue(p, flags);
                    case "reverted" -> s.lastRevert.add(p.getAttributeValue(null, "n"));
                    default -> { }
                }
            }
            s.current = flags;
        } catch (Exception e) {
            // A store that can't be read means no overrides; syncPolicy then clears the entry.
            Slog.e(TAG, "Dropping unreadable overrides", e);
            s.current = new Bundle();
        }
    }

    private static void readValue(TypedXmlPullParser p, Bundle out) {
        final String n = p.getAttributeValue(null, "n");
        final String t = p.getAttributeValue(null, "t");
        final String v = p.getAttributeValue(null, "v");
        if (n == null || t == null || v == null) return;
        switch (t) {
            case "b" -> out.putBoolean(n, Boolean.parseBoolean(v));
            case "i" -> out.putInt(n, Integer.parseInt(v));
            default -> out.putString(n, v);
        }
    }
}
