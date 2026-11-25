/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Reports which ladder rung served each package, so Laras can show an outcome instead of
 * a setting ("Protected · Patched · 2 min ago" / "Failed: …").
 *
 * <p>Stored in {@code yasvara_trickystore_rung} as
 * {@code {package: {rung, at, reason}}}. Writes are throttled: a package is rewritten at
 * most once a minute unless the rung changed. Status only — never key material.
 *
 * @hide
 */
public final class YasvaraTelemetry {
    private static final String TAG = "YasvaraTelemetry";

    static final String KEY_RUNG = "yasvara_trickystore_rung";

    private static final long WRITE_THROTTLE_MS = 60 * 1000L;
    private static final int MAX_PACKAGES = 32;

    public static final String RUNG_PASSTHROUGH = "passthrough";
    public static final String RUNG_PATCHED = "patched";
    public static final String RUNG_SYNTHESIZED = "synthesized";
    public static final String RUNG_ORIGINAL = "original";
    public static final String RUNG_FAILED = "failed";

    private static final Map<String, Long> sLastWriteMs = new HashMap<>();
    private static final Map<String, String> sLastRung = new HashMap<>();

    private YasvaraTelemetry() {}

    /** Records the outcome for {@code pkg}. Never throws. */
    public static synchronized void report(Context ctx, String pkg, String rung, String reason) {
        try {
            if (ctx == null || pkg == null || rung == null) return;
            long now = System.currentTimeMillis();
            Long lastMs = sLastWriteMs.get(pkg);
            String lastRung = sLastRung.get(pkg);
            if (rung.equals(lastRung) && lastMs != null && now - lastMs < WRITE_THROTTLE_MS) {
                return;
            }
            sLastWriteMs.put(pkg, now);
            sLastRung.put(pkg, rung);
            String raw;
            try {
                raw = Settings.Secure.getString(ctx.getContentResolver(), KEY_RUNG);
            } catch (Throwable t) {
                raw = null;
            }
            JSONObject all;
            try {
                all = (raw == null || raw.isEmpty()) ? new JSONObject() : new JSONObject(raw);
            } catch (Throwable t) {
                all = new JSONObject();
            }
            JSONObject entry = new JSONObject();
            entry.put("rung", rung);
            entry.put("at", now);
            entry.put("reason", reason == null ? "" : reason);
            all.put(pkg, entry);
            // Bound the map: drop the oldest entries past the cap.
            int over = all.length() - MAX_PACKAGES;
            if (over > 0) {
                long oldest = Long.MAX_VALUE;
                String oldestKey = null;
                Iterator<String> keys = all.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    long at = all.optJSONObject(k).optLong("at", 0L);
                    if (at < oldest) {
                        oldest = at;
                        oldestKey = k;
                    }
                }
                if (oldestKey != null) all.remove(oldestKey);
            }
            try {
                // protectChain runs in system_server on a Binder thread whose calling
                // identity is the target app (e.g. GMS uid 10164). Writing Secure with
                // that identity makes SettingsProvider reject it ("Package android
                // does not belong to <uid>") and the rung store goes stale. Drop to
                // the system's own identity for this write; in an app process this
                // is a harmless no-op.
                final long token = android.os.Binder.clearCallingIdentity();
                try {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_RUNG, all.toString());
                } finally {
                    android.os.Binder.restoreCallingIdentity(token);
                }
            } catch (Throwable t) {
                Log.w(TAG, "write failed", t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "report failed", t);
        }
    }
}
