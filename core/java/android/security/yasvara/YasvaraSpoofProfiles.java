/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one table of device identities an app can be shown, and the one per-app assignment
 * ({@code yasvara_app_spoof_map}: package -> profile id). The spoof engine applies it at process
 * start; Laras (media apps) and Pentas (games) only read and edit it through this class.
 *
 * <p>User-made profiles live in {@code yasvara_spoof_custom_profiles} as
 * {@code {id: {name, props}}} and resolve like built-in ones.
 *
 * @hide
 */
public final class YasvaraSpoofProfiles {
    private static final String TAG = "YasvaraSpoof";

    public static final String KEY_APP_SPOOF_MAP = "yasvara_app_spoof_map";
    public static final String KEY_CUSTOM_PROFILES = "yasvara_spoof_custom_profiles";

    private static final String CUSTOM_PREFIX = "custom_";

    /** A device identity: {@link android.os.Build} field name -> value. */
    public static final class Profile {
        public final String id;
        public final String name;
        public final boolean custom;
        public final Map<String, String> props;

        Profile(String id, String name, boolean custom, Map<String, String> props) {
            this.id = id;
            this.name = name;
            this.custom = custom;
            this.props = Collections.unmodifiableMap(props);
        }
    }

    private static final List<Profile> BUILT_IN = new ArrayList<>();

    static {
        // Photos original-quality backup and Snapchat (2016 Pixel).
        builtIn("marlin", "Pixel XL", "google", "Google", "Pixel XL", "marlin",
                "FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys");
        // Recent Pixel for GMS-adjacent apps.
        builtIn("kodiak", "Pixel 11 Pro XL", "google", "Google", "Pixel 11 Pro XL", "kodiak",
                "ID", "CD1A.260905.001.B1",
                "FINGERPRINT",
                "google/kodiak/kodiak:17/CD1A.260905.001.B1/16238327:user/release-keys");
        // HDR10 and high-fps media paths (e.g. Netflix).
        builtIn("s25u", "Galaxy S25 Ultra", "samsung", "samsung", "SM-S938B", "dm3q");
        // Game high-fps / graphics tiers.
        builtIn("rog9", "ROG Phone 9 Pro", "asus", "asus", "ASUS_AI2501", "AI2501");
        builtIn("rm11pro", "Red Magic 11 Pro", "nubia", "ZTE", "NX809J", null);
        builtIn("mi13pro", "Xiaomi 13 Pro", "Xiaomi", "Xiaomi", "2210132C", "nuwa");
        builtIn("bs5pro", "Black Shark 5 Pro", "blackshark", "blackshark", "SHARK KTUS-A0",
                "katyusha");
        // vivo flagship, for games that tune graphics tiers per brand.
        builtIn("vivo", "vivo X100 Pro", "vivo", "vivo", "V2324", "PD2324");
    }

    private YasvaraSpoofProfiles() {}

    private static void builtIn(String id, String name, String brand, String manufacturer,
            String model, String device, String... extra) {
        final Map<String, String> props = new LinkedHashMap<>();
        props.put("BRAND", brand);
        props.put("MANUFACTURER", manufacturer);
        props.put("MODEL", model);
        if (device != null) {
            props.put("DEVICE", device);
            props.put("PRODUCT", device);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            props.put(extra[i], extra[i + 1]);
        }
        BUILT_IN.add(new Profile(id, name, false, props));
    }

    /** Built-in then user-made profiles. */
    public static List<Profile> all(ContentResolver cr) {
        final List<Profile> out = new ArrayList<>(BUILT_IN);
        out.addAll(customProfiles(cr));
        return out;
    }

    /** The profile with this id, or null. */
    public static Profile find(ContentResolver cr, String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (Profile p : BUILT_IN) {
            if (p.id.equals(id)) return p;
        }
        if (!id.startsWith(CUSTOM_PREFIX)) return null;
        for (Profile p : customProfiles(cr)) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    static Profile builtInProfile(String id) {
        return find(null, id);
    }

    /** package -> profile id. */
    public static Map<String, String> assignments(ContentResolver cr) {
        final Map<String, String> out = new LinkedHashMap<>();
        final JSONObject map = readJson(cr, KEY_APP_SPOOF_MAP);
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            final String pkg = it.next();
            out.put(pkg, map.optString(pkg, ""));
        }
        return out;
    }

    /** The profile assigned to this package, or null. */
    public static Profile profileFor(ContentResolver cr, String pkg) {
        return find(cr, readJson(cr, KEY_APP_SPOOF_MAP).optString(pkg, null));
    }

    /**
     * Assigns a profile ({@code null} clears it). A background process of the app is stopped
     * so its next start picks the identity up; a foreground one keeps running until it restarts.
     */
    public static void assign(Context context, String pkg, String profileId) {
        final ContentResolver cr = context.getContentResolver();
        final JSONObject map = readJson(cr, KEY_APP_SPOOF_MAP);
        try {
            if (profileId == null) {
                map.remove(pkg);
            } else {
                map.put(pkg, profileId);
            }
        } catch (Exception e) {
            Log.w(TAG, "assign " + pkg + " failed", e);
            return;
        }
        Settings.Secure.putString(cr, KEY_APP_SPOOF_MAP, map.toString());
        try {
            context.getSystemService(ActivityManager.class).killBackgroundProcesses(pkg);
        } catch (Exception e) {
            Log.w(TAG, "Could not restart " + pkg, e);
        }
    }

    /** Stores a user-made profile and returns it. */
    public static Profile saveCustom(ContentResolver cr, String name, Map<String, String> props) {
        final String id = CUSTOM_PREFIX + System.currentTimeMillis();
        final JSONObject all = readJson(cr, KEY_CUSTOM_PROFILES);
        try {
            all.put(id, new JSONObject().put("name", name).put("props", new JSONObject(props)));
        } catch (Exception e) {
            Log.w(TAG, "saveCustom failed", e);
            return null;
        }
        Settings.Secure.putString(cr, KEY_CUSTOM_PROFILES, all.toString());
        return new Profile(id, name, true, new LinkedHashMap<>(props));
    }

    /** Deletes a user-made profile and clears every assignment to it. */
    public static void deleteCustom(Context context, String id) {
        final ContentResolver cr = context.getContentResolver();
        final JSONObject all = readJson(cr, KEY_CUSTOM_PROFILES);
        all.remove(id);
        Settings.Secure.putString(cr, KEY_CUSTOM_PROFILES, all.toString());
        for (Map.Entry<String, String> e : assignments(cr).entrySet()) {
            if (id.equals(e.getValue())) assign(context, e.getKey(), null);
        }
    }

    private static List<Profile> customProfiles(ContentResolver cr) {
        final List<Profile> out = new ArrayList<>();
        if (cr == null) return out;
        final JSONObject all = readJson(cr, KEY_CUSTOM_PROFILES);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            final String id = it.next();
            final JSONObject o = all.optJSONObject(id);
            if (o == null) continue;
            final JSONObject propsJson = o.optJSONObject("props");
            final Map<String, String> props = new LinkedHashMap<>();
            if (propsJson != null) {
                for (Iterator<String> k = propsJson.keys(); k.hasNext(); ) {
                    final String key = k.next();
                    props.put(key, propsJson.optString(key));
                }
            }
            out.add(new Profile(id, o.optString("name", id), true, props));
        }
        return out;
    }

    private static JSONObject readJson(ContentResolver cr, String key) {
        try {
            final String raw = cr == null ? null : Settings.Secure.getString(cr, key);
            if (!TextUtils.isEmpty(raw)) return new JSONObject(raw);
        } catch (Exception e) {
            Log.w(TAG, "Bad " + key, e);
        }
        return new JSONObject();
    }
}
