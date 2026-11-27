/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Canonical keybox publication. Callers serialize access with the service's keybox lock. */
final class YasvaraKeyboxStore {
    static final int MAX_CHARS = 200_000;
    private static final int MAX_BYTES = MAX_CHARS * 4;
    private static final Set<PosixFilePermission> PRIVATE_MODE =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> PUBLISHED_MODE =
            PosixFilePermissions.fromString("rw-r-----");

    private final Path mDirectory;
    private final Path mCanonical;
    private final Path mLegacy;
    private final FileOperations mFiles;

    YasvaraKeyboxStore() {
        this(Paths.get("/data/misc/yasvara_attest"),
                Paths.get("/data/system/yasvara/keybox.xml"), new FileOperations());
    }

    YasvaraKeyboxStore(Path directory, Path legacy, FileOperations files) {
        mDirectory = directory;
        mCanonical = directory.resolve("keybox.xml");
        mLegacy = legacy;
        mFiles = files;
    }

    static String normalize(String raw) {
        if (raw == null || raw.length() > MAX_CHARS) {
            throw new IllegalArgumentException("keybox payload rejected");
        }
        String xml = raw.trim();
        if (!xml.startsWith("<")) {
            try {
                xml = new String(Base64.getDecoder().decode(xml.replaceAll("\\s", "")),
                        StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("keybox is not XML or base64 XML");
            }
        }
        if (!xml.startsWith("<") || xml.length() > MAX_CHARS) {
            throw new IllegalArgumentException("keybox is not XML or base64 XML");
        }
        return xml;
    }

    /** Validation runs before migration; an invalid canonical file never falls back to legacy. */
    <T> void load(Function<String, T> parse, Consumer<T> onLoaded) throws IOException {
        requireDirectory();
        String raw = readIfPresent(mCanonical);
        boolean migrating = raw == null;
        if (migrating) raw = readIfPresent(mLegacy);
        if (raw == null) return;

        // An empty canonical file is a committed clear, not permission to migrate again.
        if (!migrating && raw.isEmpty()) {
            onLoaded.accept(null);
        } else {
            String xml = normalize(raw);
            T parsed = parse.apply(xml);
            if (migrating || !raw.equals(xml)) {
                publish(xml, () -> onLoaded.accept(parsed));
            } else {
                onLoaded.accept(parsed);
            }
        }
        cleanupLegacy();
    }

    void install(String xml, Runnable onCommitted) throws IOException {
        publish(normalize(xml), onCommitted);
        cleanupLegacy();
    }

    void clear(Runnable onCommitted) throws IOException {
        // Keep a tombstone at the canonical path: even failed legacy cleanup cannot resurrect it.
        publish("", onCommitted);
        cleanupLegacy();
    }

    private void requireDirectory() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(mDirectory,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException("canonical keybox directory is not an init-created directory");
        }
        // Do not mkdir/chown here: init supplies system:keystore ownership and setgid inheritance.
    }

    private static String readIfPresent(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("stored keybox is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Derived, non-secret native configuration (e.g. text configs), replaced atomically. */
    void publishConfig(String name, String content) throws IOException {
        publishBytes(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Derived binary snapshot (e.g. "policy.bin"), replaced atomically (0640 system:keystore). */
    void publishBytes(String name, byte[] content) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.startsWith(".")) {
            throw new IllegalArgumentException("bad config name");
        }
        publish(mDirectory.resolve(name), content, () -> { });
    }

    /** Deletes an obsolete derived config file if present (0 writes once removed). */
    void deleteConfig(String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.startsWith(".")) {
            return;
        }
        try {
            if (mFiles.deleteIfExists(mDirectory.resolve(name))) {
                mFiles.syncDirectory(mDirectory);
            }
        } catch (IOException ignored) {
        }
    }

    private void publish(String xml, Runnable onCommitted) throws IOException {
        publish(mCanonical, xml.getBytes(StandardCharsets.UTF_8), onCommitted);
    }

    private void publish(Path target, byte[] bytes, Runnable onCommitted) throws IOException {
        requireDirectory();
        Path temporary = Files.createTempFile(mDirectory, ".keybox-", ".tmp",
                PosixFilePermissions.asFileAttribute(PRIVATE_MODE));
        boolean committed = false;
        try {
            mFiles.writeAndSync(temporary, bytes);
            mFiles.replace(temporary, target);
            committed = true;
            // Publication is the commit point. Keep RAM coherent even if later fsync/cleanup fails.
            onCommitted.run();
            mFiles.syncDirectory(mDirectory);
        } catch (IOException | RuntimeException e) {
            if (committed) {
                throw new IOException("canonical keybox state committed, but durability failed", e);
            }
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private void cleanupLegacy() throws IOException {
        try {
            if (mFiles.deleteIfExists(mLegacy)) {
                mFiles.syncDirectory(mLegacy.getParent());
            }
        } catch (IOException e) {
            throw new IOException("canonical keybox state active, but legacy cleanup failed", e);
        }
    }

    /** Small I/O seam for deterministic host failure tests; no Android or private-key dependency. */
    static class FileOperations {
        void writeAndSync(Path path, byte[] bytes) throws IOException {
            try (FileChannel output = FileChannel.open(path, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                Files.setPosixFilePermissions(path, PUBLISHED_MODE);
                output.force(true);
            }
        }

        void replace(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }

        void syncDirectory(Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        }

        boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }
    }
}
