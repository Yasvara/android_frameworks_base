/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java SSOT for Tabir one-tap Observer Risk / DFP Cache Reset.
 *
 * <p>Shared between {@code YasvaraAppVisibilityService} (writer of {@link #KEY_RISK_CACHE_RESET}
 * in {@code system_server}) and {@code YasvaraSpoofEngine} (in-process executor before
 * {@code Application.onCreate} under the app's own UID). Zero Android framework dependencies so
 * host unit tests exercise the exact production bytecode on real simulated app data directories.
 *
 * <p>Uses strictly neutral filenames ({@code .app_cache_gen} / {@code .app_cache_gen.lock}) inside
 * {@code no_backup/} so zero ROM markers are ever written into app-private storage.
 *
 * @hide
 */
public final class YasvaraRiskCacheReset {
    /** Neutral Settings.Secure key storing per-package reset epochs ("pkg=ms,..."). */
    public static final String KEY_RISK_CACHE_RESET = "app_risk_cache_reset_gen";
    public static final String STAMP_FILE = ".app_cache_gen";
    public static final String LOCK_FILE = ".app_cache_gen.lock";
    public static final long ENTRY_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private YasvaraRiskCacheReset() {}

    public static boolean isValidPackageName(String packageName) {
        if (packageName == null || packageName.isEmpty() || "android".equals(packageName)) {
            return false;
        }
        for (int i = 0; i < packageName.length(); i++) {
            char c = packageName.charAt(i);
            if (c == ',' || c == '=' || c == '/' || c == '\\' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return !packageName.contains("..");
    }

    /**
     * Merges a new reset epoch for {@code pkg} into {@code existing} ("pkg=ms,...") while pruning
     * malformed entries and entries older than {@link #ENTRY_TTL_MS}.
     */
    public static String mergePackageEpoch(String existing, String pkg, long nowMs) {
        LinkedHashMap<String, Long> map = new LinkedHashMap<>();
        if (existing != null && !existing.isEmpty()) {
            String[] parts = existing.split(",");
            for (String entry : parts) {
                int eq = entry.indexOf('=');
                if (eq <= 0 || eq >= entry.length() - 1) continue;
                String k = entry.substring(0, eq).trim();
                if (!isValidPackageName(k)) continue;
                try {
                    long ts = Long.parseLong(entry.substring(eq + 1).trim());
                    if (ts > 0 && nowMs - ts <= ENTRY_TTL_MS) {
                        map.put(k, ts);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (isValidPackageName(pkg)) {
            map.put(pkg, nowMs);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Extracts the pending reset epoch string for {@code pkg} from {@code raw}, or {@code null}
     * if absent/malformed. Fast-paths to zero allocations when {@code pkg} is not present.
     */
    public static String parsePackageEpoch(String raw, String pkg) {
        if (raw == null || raw.isEmpty() || pkg == null || pkg.isEmpty() || !raw.contains(pkg)) {
            return null;
        }
        int start = 0;
        final int len = raw.length();
        while (start < len) {
            int comma = raw.indexOf(',', start);
            int end = comma < 0 ? len : comma;
            int eq = raw.indexOf('=', start);
            if (eq > start && eq < end) {
                int ks = start;
                int ke = eq;
                while (ks < ke && raw.charAt(ks) <= ' ') ks++;
                while (ke > ks && raw.charAt(ke - 1) <= ' ') ke--;
                if (ke - ks == pkg.length() && raw.regionMatches(ks, pkg, 0, pkg.length())) {
                    String val = raw.substring(eq + 1, end).trim();
                    if (isAllDigits(val)) {
                        return val;
                    }
                    return null;
                }
            }
            start = end + 1;
        }
        return null;
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static boolean applyIfNeeded(
            File ceDataDir, File deDataDir, String packageName, String rawSetting, int pid)
            throws IOException {
        return applyIfNeeded(ceDataDir, deDataDir, packageName, rawSetting, pid, null);
    }

    /**
     * Executes a pending risk/DFP cache reset for {@code packageName} if {@code rawSetting}
     * carries an epoch newer than the local neutral stamp file.
     *
     * <p>When non-null, {@code onResetLocked} runs inside the cross-process {@link FileLock}
     * before the stamp is updated so sibling processes cannot start {@code Application.onCreate}
     * while stale keystore attestation aliases are still being purged.
     *
     * @return {@code true} if caches were purged and the stamp was updated; {@code false} if
     *     no reset was pending or the epoch was already applied.
     */
    public static boolean applyIfNeeded(
            File ceDataDir,
            File deDataDir,
            String packageName,
            String rawSetting,
            int pid,
            Runnable onResetLocked)
            throws IOException {
        final String targetEpoch = parsePackageEpoch(rawSetting, packageName);
        if (targetEpoch == null) return false;
        if (ceDataDir == null || !ceDataDir.isDirectory()) return false;

        File stampDir = new File(ceDataDir, "no_backup");
        if (!stampDir.isDirectory() && !stampDir.mkdirs()) {
            stampDir = ceDataDir;
        }
        File stampFile = new File(stampDir, STAMP_FILE);
        if (targetEpoch.equals(readStamp(stampFile))) {
            return false;
        }

        File lockFile = new File(stampDir, LOCK_FILE);
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
             FileLock ignoredLock = raf.getChannel().lock()) {
            if (targetEpoch.equals(readStamp(stampFile))) {
                return false;
            }
            purgeObserverRiskDirs(ceDataDir, packageName);
            if (deDataDir != null && deDataDir.isDirectory() && !deDataDir.equals(ceDataDir)) {
                purgeObserverRiskDirs(deDataDir, packageName);
            }
            if (onResetLocked != null) {
                onResetLocked.run();
            }
            writeStampAtomic(stampDir, stampFile, targetEpoch, pid);
            return true;
        }
    }

    /**
     * Returns true when {@code alias} identifies a protected user/biometric/payment/session
     * keystore entry that must NEVER be inspected or deleted during risk-cache reset.
     */
    public static boolean isProtectedKeyAlias(String alias) {
        if (alias == null || alias.isEmpty()) return true;
        String lower = alias.toLowerCase(Locale.ROOT);
        return lower.contains("biometric")
                || lower.contains("encrypt")
                || lower.contains("decrypt")
                || lower.contains("touch_id")
                || lower.contains("face_id")
                || lower.contains("cipher")
                || lower.contains("pin")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("session")
                || lower.contains("wallet")
                || lower.contains("payment")
                || lower.contains("sdb-");
    }

    /**
     * Returns true when {@code alias} is an app-level KeyAttestation alias (e.g. Shopee/SeaBank
     * {@code KeyAttestation_persistent} / {@code KeyAttestation}) that can retain a stale
     * unmanaged or expired hardware attestation chain and should be deleted on risk-cache reset
     * so Keystore2 provisions a fresh TrickyStore-managed keypair on next use.
     */
    public static boolean isStaleAttestationAlias(String alias) {
        if (isProtectedKeyAlias(alias)) return false;
        String lower = alias.toLowerCase(Locale.ROOT);
        return lower.startsWith("keyattestation")
                || lower.equals("attest_key")
                || lower.startsWith("attest_key_")
                || lower.endsWith("_attest_key")
                || lower.contains("key_attestation")
                || lower.contains("keyattest")
                || (lower.contains("shps") && lower.contains("attest"));
    }

    public static void purgeObserverRiskDirs(File rootDir, String packageName) {
        if (rootDir == null || !rootDir.isDirectory()) return;
        clearDirChildren(new File(rootDir, "cache"));
        clearDirChildren(new File(rootDir, "code_cache"));

        final boolean isShopeeOrSeaBank = packageName != null
                && (packageName.startsWith("com.shopee.")
                        || packageName.startsWith("id.co.bankbkemobile."));

        purgeRiskMatchingEntries(new File(rootDir, "files/mmkv"), isShopeeOrSeaBank);
        purgeRiskMatchingEntries(new File(rootDir, "shared_prefs"), isShopeeOrSeaBank);

        if (isShopeeOrSeaBank) {
            File dbDir = new File(rootDir, "databases");
            File[] dbs = dbDir.listFiles();
            if (dbs != null) {
                for (File f : dbs) {
                    String lower = f.getName().toLowerCase(Locale.ROOT);
                    if (lower.startsWith("seabank_event.db") || lower.contains("shps")) {
                        deleteSafe(f);
                    }
                }
            }
            File filesDir = new File(rootDir, "files");
            File[] files = filesDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String lower = f.getName().toLowerCase(Locale.ROOT);
                    if (lower.startsWith("shps") || lower.contains("seabank_dfp")) {
                        deleteSafe(f);
                    }
                }
            }
        }
    }

    private static void clearDirChildren(File dir) {
        if (dir == null || !dir.isDirectory() || Files.isSymbolicLink(dir.toPath())) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            deleteSafe(child);
        }
    }

    private static void purgeRiskMatchingEntries(File dir, boolean isShopeeOrSeaBank) {
        if (dir == null || !dir.isDirectory() || Files.isSymbolicLink(dir.toPath())) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String lower = child.getName().toLowerCase(Locale.ROOT);
            boolean match = lower.contains("dfp")
                    || lower.contains("shps")
                    || lower.contains("trustdecision")
                    || lower.contains("tongdun")
                    || lower.contains("fraudmetrix")
                    || lower.contains("device_fingerprint")
                    || lower.contains("device_fp")
                    || lower.contains("security_sdk")
                    || lower.contains("security_detect")
                    || lower.contains("risk_control")
                    || lower.contains("risk_cache");
            if (!match && isShopeeOrSeaBank) {
                match = lower.startsWith("seabank_live_")
                        || lower.startsWith("default_seabank_mmapid")
                        || lower.startsWith("seabank_tracker_")
                        || lower.startsWith("sphelper_sp_main.xml")
                        || lower.startsWith("sphelper_sp_main_bank.xml")
                        || lower.contains("seabank_risk");
            }
            if (match) {
                deleteSafe(child);
            }
        }
    }

    /** Deletes a file or directory without ever following symlinks outside the target tree. */
    private static void deleteSafe(File target) {
        if (target == null) return;
        if (Files.isSymbolicLink(target.toPath())) {
            target.delete();
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteSafe(child);
                }
            }
        }
        target.delete();
    }

    private static String readStamp(File stampFile) {
        if (!stampFile.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(stampFile))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void writeStampAtomic(File dir, File stampFile, String stamp, int pid)
            throws IOException {
        File tmp = new File(dir, stampFile.getName() + ".tmp." + pid);
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(stampFile)) {
            tmp.delete();
            try (FileOutputStream out = new FileOutputStream(stampFile, false)) {
                out.write(stamp.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
        }
    }
}
