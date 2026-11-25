/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.nada;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.audiofx.AudioEffect;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;

import com.android.internal.util.DumpUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Nada processor effects: stores a profile per device class and per app and hands the one in
 * force to the engine in audioserver ({@code AudioSystem.setParameters("nada_*")},
 * frameworks/av/services/nada). The engine owns the settings schema; this service checks keys
 * against it and otherwise treats a profile as text.
 *
 * <p>It is also the one controller of Dolby Atmos: the "dolby.*" keys of the profile in force
 * on the playing device go to the DAP effect, whose processing runs on the audio DSP.
 */
public final class NadaService extends INadaManager.Stub {
    private static final String TAG = "Nada";
    private static final String PERMISSION = "yasvara.permission.MANAGE_SWARA";
    private static final String OFF = "on:0";
    private static final int MAX_SPEC = 4096;
    private static final int MAX_IMPULSE_BYTES = 8 << 20;
    private static final int CHUNK = 24000;

    private static final String DOLBY = Nada.DOLBY;
    private static final UUID DAP = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");
    // Parameter ids of the DAP effect (DAX3).
    private static final int DAP_VALUES = 5;
    private static final int DAP_ENABLE = 0;
    private static final int DAP_PROFILE = 0xA000000;
    private static final int DAP_SET_PROFILE_PARAM = 0x1000000;
    // Schema rows this service adds for Dolby, in the engine's row format. The last column of
    // DOLBY_PARAMS is the DAP profile parameter; 0 is handled by name.
    private static final String DOLBY_GROUP = "g\tdolby\tDolby Atmos\tdsp\tRuns on the audio DSP chip,"
            + " after the processor effects. Music and video only: games and fast audio never reach it.\n";
    private static final String[][] DOLBY_PARAMS = {
            {"dolby.on", "bool\t0\t1\t1\t1\t\tDolby Atmos", "0"},
            {"dolby.profile", "enum\t0\t8\t1\t0\tDynamic,Movie,Music,Custom,Mobility,"
                    + "On the go,Commute,Travel,Voice\tProfile", "0"},
            {"dolby.virtualizer", "bool\t0\t1\t1\t1\t\tSurround virtualiser", "0"},
            {"dolby.dialogue", "bool\t0\t1\t1\t0\t\tDialogue enhancer", "105"},
            {"dolby.dialogue.amount", "num\t0\t16\t1\t6\t\tDialogue amount", "108"},
            {"dolby.bass", "bool\t0\t1\t1\t0\t\tBass enhancer", "111"},
            {"dolby.leveler", "bool\t0\t1\t1\t0\t\tVolume leveller", "103"},
            {"dolby.leveler.amount", "num\t0\t10\t1\t2\t\tLeveller amount", "116"},
            {"dolby.widening", "num\t4\t10\t1\t4\t\tStereo widening", "113"},
    };
    private static final int DAP_HEADPHONE_VIRTUALIZER = 101;
    private static final int DAP_SPEAKER_VIRTUALIZER = 102;

    private final Context mContext;
    private final Handler mHandler;
    private final Object mLock = new Object();
    private final ArrayMap<String, String> mProfiles = new ArrayMap<>();
    private final ArrayMap<String, String> mPushed = new ArrayMap<>();
    private final AtomicFile mFile =
            new AtomicFile(new File(Environment.getDataSystemDirectory(), "yasvara_nada.json"));
    private final File mImpulseDir =
            new File(Environment.getDataSystemDirectory(), "yasvara_nada_ir");
    @Nullable
    private Nada.Schema mSchema;
    @Nullable
    private String mTopPackage;
    private String mPushedMmap = "";
    // Handler thread only.
    @Nullable
    private AudioEffect mDap;
    private String mDolbyApplied = "";
    private Boolean mDolbyAvailable;

    public NadaService(@NonNull Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mHandler.post(this::initWhenAudioReady);
    }

    private void initWhenAudioReady() {
        // The audio service comes up after this one is published.
        if (mContext.getSystemService(AudioManager.class) == null
                || android.os.ServiceManager.checkService(Context.AUDIO_SERVICE) == null) {
            mHandler.postDelayed(this::initWhenAudioReady, 500);
            return;
        }
        try {
            load();
            final AudioManager am = mContext.getSystemService(AudioManager.class);
            am.setAudioServerStateCallback(mHandler::post,
                    new AudioManager.AudioServerStateCallback() {
                        @Override
                        public void onAudioServerUp() {
                            // audioserver starts with every profile off and no impulse responses.
                            synchronized (mLock) {
                                mPushed.clear();
                                mPushedMmap = "";
                                mSchema = null;
                            }
                            releaseDap();
                            uploadAllImpulses();
                            push();
                        }
                    });
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onTaskStackChanged() {
                    mHandler.post(NadaService.this::updateTop);
                }
            });
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    mHandler.postDelayed(NadaService.this::push, 300);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    mHandler.postDelayed(NadaService.this::push, 300);
                }
            }, mHandler);
            uploadAllImpulses();
            updateTop();
            push();
        } catch (RuntimeException | RemoteException e) {
            Slog.e(TAG, "init failed; audio plays unprocessed", e);
        }
    }

    private void load() {
        try (InputStream in = mFile.openRead()) {
            final JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            synchronized (mLock) {
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    final String scope = it.next();
                    mProfiles.put(scope, o.getString(scope));
                }
            }
        } catch (IOException | org.json.JSONException e) {
            // No file yet, or an unreadable one: start with no profiles.
        }
    }

    private void saveLocked() {
        FileOutputStream out = null;
        try {
            out = mFile.startWrite();
            out.write(new JSONObject(mProfiles).toString().getBytes(StandardCharsets.UTF_8));
            mFile.finishWrite(out);
        } catch (IOException e) {
            mFile.failWrite(out);
            Slog.w(TAG, "profiles not saved", e);
        }
    }

    private void updateTop() {
        String top = null;
        try {
            final ActivityTaskManager.RootTaskInfo info =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info != null && info.topActivity != null) top = info.topActivity.getPackageName();
        } catch (RemoteException e) {
            // system_server local call
        }
        synchronized (mLock) {
            if (java.util.Objects.equals(top, mTopPackage)) return;
            mTopPackage = top;
        }
        push();
    }

    // Sends each device class the profile in force: the front app's when it has one.
    private void push() {
        final List<String> out = new ArrayList<>();
        String mmap;
        synchronized (mLock) {
            final String app = mTopPackage == null ? null : mProfiles.get(Nada.SCOPE_APP + mTopPackage);
            for (String route : Nada.ROUTES) {
                final String spec = app != null ? app : mProfiles.getOrDefault(route, OFF);
                if (!spec.equals(mPushed.get(route))) {
                    mPushed.put(route, spec);
                    out.add("nada_cfg=" + route + "|" + engineSpec(spec));
                }
            }
            // An app with its own profile must reach a mixer, so it gets no MMAP stream.
            final StringBuilder uids = new StringBuilder();
            final PackageManager pm = mContext.getPackageManager();
            for (int i = 0; i < mProfiles.size(); i++) {
                final String scope = mProfiles.keyAt(i);
                if (!scope.startsWith(Nada.SCOPE_APP)) continue;
                try {
                    final int uid = pm.getPackageUidAsUser(scope.substring(Nada.SCOPE_APP.length()),
                            ActivityManager.getCurrentUser());
                    if (uids.length() > 0) uids.append(',');
                    uids.append(uid);
                } catch (PackageManager.NameNotFoundException e) {
                    // Uninstalled; its profile waits for a reinstall.
                }
            }
            mmap = uids.toString();
            if (mmap.equals(mPushedMmap)) mmap = null;
            else mPushedMmap = mmap;
        }
        for (String kv : out) AudioSystem.setParameters(kv);
        if (mmap != null) AudioSystem.setParameters("nada_mmap=" + mmap);
        if (Thread.currentThread() == mHandler.getLooper().getThread()) applyDolby();
        else mHandler.post(this::applyDolby);
    }

    // The engine does not know the Dolby keys.
    private static String engineSpec(String spec) {
        final Map<String, String> v = Nada.decode(spec);
        v.keySet().removeIf(k -> k.startsWith(DOLBY));
        return Nada.encode(v);
    }

    private boolean dolbyAvailable() {
        if (mDolbyAvailable == null) {
            boolean found = false;
            final AudioEffect.Descriptor[] all = AudioEffect.queryEffects();
            if (all != null) {
                for (AudioEffect.Descriptor d : all) found |= DAP.equals(d.uuid) || DAP.equals(d.type);
            }
            mDolbyAvailable = found;
        }
        return mDolbyAvailable;
    }

    private void releaseDap() {
        if (mDap != null) mDap.release();
        mDap = null;
        mDolbyApplied = "";
    }

    private void dapSet(int... words) {
        final ByteBuffer b = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int w : words) b.putInt(w);
        final int status = mDap.setParameter(DAP_VALUES, b.array());
        if (status != AudioEffect.SUCCESS) throw new IllegalStateException("DAP status " + status);
    }

    private int dapProfile() {
        final byte[] buf = new byte[12];
        mDap.getParameter(DAP_VALUES + DAP_PROFILE, buf);
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    // Sends the Dolby keys in force on the playing device to the DAP effect: the device's,
    // then the front app's over them. A key neither sets keeps the value of the Dolby tuning.
    private void applyDolby() {
        if (!dolbyAvailable()) return;
        final String route = currentRoute();
        final Map<String, String> v;
        synchronized (mLock) {
            v = Nada.decode(mProfiles.getOrDefault(route, ""));
            if (mTopPackage != null) {
                Nada.decode(mProfiles.get(Nada.SCOPE_APP + mTopPackage)).forEach((k, x) -> {
                    if (k.startsWith(DOLBY)) v.put(k, x);
                });
            }
        }
        v.keySet().removeIf(k -> !k.startsWith(DOLBY));
        final String want = route + "|" + Nada.encode(v);
        if (want.equals(mDolbyApplied)) return;
        try {
            if (mDap == null) {
                mDap = new AudioEffect(AudioEffect.EFFECT_TYPE_NULL, DAP, 100, 0);
            }
            final boolean on = !"0".equals(v.get("dolby.on"));
            dapSet(DAP_ENABLE, 1, on ? 1 : 0);
            mDap.setEnabled(on);
            if (on) {
                if (v.containsKey("dolby.profile")) {
                    dapSet(DAP_PROFILE, 1, Math.round(Float.parseFloat(v.get("dolby.profile"))));
                }
                final int profile = dapProfile();
                for (String[] row : DOLBY_PARAMS) {
                    final String value = v.get(row[0]);
                    int id = Integer.parseInt(row[2]);
                    if (row[0].equals("dolby.virtualizer")) {
                        id = route.equals("spk") ? DAP_SPEAKER_VIRTUALIZER : DAP_HEADPHONE_VIRTUALIZER;
                    }
                    if (value == null || id == 0) continue;
                    dapSet(DAP_SET_PROFILE_PARAM, 2, profile, id, Math.round(Float.parseFloat(value)));
                }
            }
            mDolbyApplied = want;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Dolby not applied", e);
            releaseDap();
        }
    }

    private Nada.Schema schema() {
        synchronized (mLock) {
            if (mSchema == null || mSchema.params.isEmpty()) {
                mSchema = Nada.Schema.parse(schemaText());
            }
            return mSchema;
        }
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(PERMISSION, TAG);
    }

    private static String checkScope(String scope) {
        if (scope != null) {
            for (String r : Nada.ROUTES) if (r.equals(scope)) return scope;
            if (scope.startsWith(Nada.SCOPE_APP) && scope.length() > Nada.SCOPE_APP.length()
                    && scope.length() < 256 && scope.matches("app:[A-Za-z0-9_.]+")) {
                return scope;
            }
        }
        throw new IllegalArgumentException("bad scope " + scope);
    }

    private static String checkName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,64}") || name.startsWith(".")) {
            throw new IllegalArgumentException("bad name " + name);
        }
        return name;
    }

    // The engine's rows, then the Dolby rows where the phone has the effect.
    private String schemaText() {
        final StringBuilder b = new StringBuilder(AudioSystem.getParameters("nada_schema"));
        if (dolbyAvailable()) {
            b.append(DOLBY_GROUP);
            for (String[] row : DOLBY_PARAMS) {
                final String[] f = row[1].split("\t", -1);
                // key, group, then type, min, max, step, default, unit, title
                b.append("p\t").append(row[0]).append("\tdolby\t").append(String.join("\t", f))
                        .append('\n');
            }
        }
        return b.toString();
    }

    @Override
    public String getSchema() {
        enforce();
        return schemaText();
    }

    @Override
    public String getProfile(String scope) {
        enforce();
        synchronized (mLock) {
            return mProfiles.getOrDefault(checkScope(scope), "");
        }
    }

    @Override
    public void setProfile(String scope, String spec) {
        enforce();
        checkScope(scope);
        if (spec == null || spec.length() > MAX_SPEC || spec.matches("(?s).*[;=\\n\\r\\t].*")) {
            throw new IllegalArgumentException("bad profile");
        }
        final Nada.Schema schema = schema();
        for (String key : Nada.decode(spec).keySet()) {
            if (!schema.params.containsKey(key)) {
                throw new IllegalArgumentException("unknown setting " + key);
            }
        }
        synchronized (mLock) {
            mProfiles.put(scope, spec);
            saveLocked();
        }
        mHandler.post(this::push);
    }

    @Override
    public void clearProfile(String scope) {
        enforce();
        synchronized (mLock) {
            if (mProfiles.remove(checkScope(scope)) == null) return;
            saveLocked();
        }
        mHandler.post(this::push);
    }

    @Override
    public String getRoute() {
        enforce();
        return currentRoute();
    }

    private String currentRoute() {
        final AudioManager am = mContext.getSystemService(AudioManager.class);
        final List<AudioDeviceAttributes> devs = am.getDevicesForAttributes(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
        if (devs.isEmpty()) return "spk";
        return switch (devs.get(0).getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "spk";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_LINE_ANALOG -> "wired";
            case AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_DOCK -> "usb";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
                    AudioDeviceInfo.TYPE_HEARING_AID -> "bt";
            default -> "other";
        };
    }

    @Override
    public String getState() {
        enforce();
        final String top;
        synchronized (mLock) {
            top = mTopPackage != null && mProfiles.containsKey(Nada.SCOPE_APP + mTopPackage)
                    ? mTopPackage : "";
        }
        return AudioSystem.getParameters("nada_dump") + "app_profile " + top + "\n";
    }

    private File impulseFile(String name, int rate, int channels) {
        return new File(mImpulseDir, name + "@" + rate + "@" + channels);
    }

    @Override
    public void putImpulse(String name, int rate, int channels, ParcelFileDescriptor data) {
        enforce();
        checkName(name);
        if (name.contains("@") || rate < 8000 || rate > 384000
                || (channels != 1 && channels != 2 && channels != 4) || data == null) {
            throw new IllegalArgumentException("bad impulse response");
        }
        final byte[] bytes;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(data)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[65536];
            for (int n; (n = in.read(chunk)) > 0; ) {
                if (buf.size() + n > MAX_IMPULSE_BYTES) throw new IllegalArgumentException("too long");
                buf.write(chunk, 0, n);
            }
            bytes = buf.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable", e);
        }
        if (bytes.length == 0 || bytes.length % (4 * channels) != 0) {
            throw new IllegalArgumentException("bad length");
        }
        removeImpulseFiles(name);
        mImpulseDir.mkdirs();
        try {
            Files.write(impulseFile(name, rate, channels).toPath(), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("not stored", e);
        }
        mHandler.post(() -> {
            upload(name, rate, channels, bytes);
            synchronized (mLock) {
                mPushed.clear();
            }
            push();
        });
    }

    // The engine checks the count, the checksum and that every sample is finite before use.
    private static void upload(String name, int rate, int channels, byte[] bytes) {
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        AudioSystem.setParameters(String.format("nada_ir=%s,%d,%d,%d,%08x", name, rate, channels,
                bytes.length / 4 / channels, crc.getValue()));
        for (int off = 0; off < bytes.length; off += CHUNK) {
            final int n = Math.min(CHUNK, bytes.length - off);
            AudioSystem.setParameters("nada_ird=" + name + "," + off / 4 + ","
                    + Base64.encodeToString(bytes, off, n, Base64.NO_WRAP));
        }
        AudioSystem.setParameters("nada_ire=" + name);
    }

    private void uploadAllImpulses() {
        final File[] files = mImpulseDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            final String[] p = f.getName().split("@");
            if (p.length != 3) continue;
            try {
                upload(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Files.readAllBytes(f.toPath()));
            } catch (IOException | NumberFormatException e) {
                Slog.w(TAG, "impulse response " + f.getName() + " skipped", e);
            }
        }
    }

    private void removeImpulseFiles(String name) {
        final File[] files = mImpulseDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(name + "@")) f.delete();
        }
    }

    @Override
    public String[] listImpulses() {
        enforce();
        final List<String> names = new ArrayList<>();
        final File[] files = mImpulseDir.listFiles();
        if (files != null) {
            for (File f : files) names.add(f.getName().split("@")[0]);
        }
        return names.toArray(new String[0]);
    }

    @Override
    public void removeImpulse(String name) {
        enforce();
        removeImpulseFiles(checkName(name));
        mHandler.post(() -> AudioSystem.setParameters("nada_irx=" + name));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        synchronized (mLock) {
            pw.println("front app: " + mTopPackage);
            pw.println("dolby: " + mDolbyApplied);
            for (Map.Entry<String, String> e : mProfiles.entrySet()) {
                pw.println("profile " + e.getKey() + " " + e.getValue());
            }
        }
        pw.print(AudioSystem.getParameters("nada_dump"));
    }
}
