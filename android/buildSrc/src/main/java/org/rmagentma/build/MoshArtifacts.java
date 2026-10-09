/*
 * rmAgentMa
 * Copyright 2026 rmAgentMa contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rmagentma.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MoshArtifacts {
    static final List<String> ABIS = List.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
    private static final String TERMINFO = "share/terminfo/x/xterm-256color";
    private static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;

    @FunctionalInterface
    interface Download {
        void download(URI uri, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface Move {
        void move(Path source, Path destination) throws IOException;
    }

    private MoshArtifacts() {}

    public static void prepare(String tag, Path cache, Path jniLibs, Path assets) throws IOException {
        prepare(tag, cache, jniLibs, assets, MoshArtifacts::download, Thread::sleep, MoshArtifacts::move);
    }

    static void prepare(String tag, Path cache, Path jniLibs, Path assets, Download download, Pause pause, Move move)
            throws IOException {
        if (!tag.matches("android-[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")) {
            throw new IOException("Invalid mosh release tag: " + tag);
        }
        Path output = jniLibs.toAbsolutePath().normalize().getParent();
        if (!output.equals(assets.toAbsolutePath().normalize().getParent())
                || !jniLibs.getFileName().toString().equals("jniLibs")
                || !assets.getFileName().toString().equals("assets")) {
            throw new IOException("Mosh outputs must be sibling jniLibs and assets directories");
        }
        Path releaseCache = cache.resolve(tag);
        Files.createDirectories(releaseCache);
        for (String abi : ABIS) {
            ensureArchive(tag, abi, releaseCache, download, pause);
        }
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), "." + output.getFileName() + "-staging-");
        try {
            for (String abi : ABIS) {
                Path archive = releaseCache.resolve(abi + ".zip");
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    Path client = staging.resolve("jniLibs").resolve(abi).resolve("libmoshexec.so");
                    Files.createDirectories(client.getParent());
                    copyEntry(zip, zip.getEntry("mosh-client"), client);
                    if (abi.equals(ABIS.get(0))) {
                        Path nested = Files.createTempFile(releaseCache, "terminfo-extract-", ".zip");
                        try {
                            copyEntry(zip, zip.getEntry("terminfo.zip"), nested);
                            try (ZipFile terminfo = new ZipFile(nested.toFile())) {
                                Path target = staging.resolve("assets").resolve(TERMINFO);
                                Files.createDirectories(target.getParent());
                                copyEntry(terminfo, terminfo.getEntry(TERMINFO), target);
                            }
                        } finally {
                            Files.deleteIfExists(nested);
                        }
                    }
                }
            }
            Files.writeString(staging.resolve("assets/mosh-release.txt"), tag);
            commit(staging, output, move);
        } finally {
            deleteTree(staging);
        }
    }

    private static void ensureArchive(String tag, String abi, Path cache, Download download, Pause pause)
            throws IOException {
        Path archive = cache.resolve(abi + ".zip");
        if (Files.exists(archive)) {
            try {
                validateArchive(archive);
                return;
            } catch (IOException invalid) {
                Files.delete(archive);
            }
        }
        URI uri = URI.create("https://github.com/connectbot/mosh4android/releases/download/" + tag
                + "/mosh-android-" + abi + ".zip");
        IOException failure = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) {
                try {
                    pause.sleep(1000L << (attempt - 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted downloading mosh " + tag + "/" + abi);
                }
            }
            Path part = Files.createTempFile(cache, abi + "-", ".part");
            try {
                download.download(uri, part);
                validateArchive(part);
                move(part, archive);
                return;
            } catch (PermanentDownloadException permanent) {
                throw new IOException("Cannot download mosh " + tag + "/" + abi + " from " + uri, permanent);
            } catch (IOException retryable) {
                failure = retryable;
                if (Thread.currentThread().isInterrupted()) {
                    throw retryable;
                }
            } finally {
                Files.deleteIfExists(part);
            }
        }
        throw new IOException("Mosh download failed after 4 attempts: " + tag + "/" + abi + " from " + uri, failure);
    }

    static void validateArchive(Path archive) throws IOException {
        if (Files.size(archive) == 0 || Files.size(archive) > MAX_ARCHIVE_BYTES) {
            throw new IOException("Invalid mosh archive size: " + archive);
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            validateEntries(zip);
            requireEntry(zip, "mosh-client");
            requireEntry(zip, "terminfo.zip");
            Path nested = Files.createTempFile(archive.getParent(), "terminfo-validate-", ".zip");
            try {
                copyEntry(zip, zip.getEntry("terminfo.zip"), nested);
                try (ZipFile terminfo = new ZipFile(nested.toFile())) {
                    validateEntries(terminfo);
                    requireEntry(terminfo, TERMINFO);
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
    }

    private static void validateEntries(ZipFile zip) throws IOException {
        Set<String> names = new HashSet<>();
        long total = 0;
        var entries = zip.entries();
        byte[] buffer = new byte[8192];
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!names.add(entry.getName()) || names.size() > 10000) {
                throw new IOException("Duplicate or excessive ZIP entries: " + zip.getName());
            }
            CRC32 crc = new CRC32();
            long size = 0;
            try (InputStream input = zip.getInputStream(entry)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    total += count;
                    if (total > MAX_EXPANDED_BYTES) {
                        throw new IOException("ZIP expanded size limit exceeded: " + zip.getName());
                    }
                    crc.update(buffer, 0, count);
                }
            }
            if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                throw new IOException("ZIP size/CRC mismatch: " + entry.getName());
            }
        }
    }

    private static void requireEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() <= 0) {
            throw new IOException("Missing or empty ZIP entry " + name + " in " + zip.getName());
        }
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void download(URI uri, Path destination) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || !("github.com".equals(host)
                    || "release-assets.githubusercontent.com".equals(host)
                    || "objects.githubusercontent.com".equals(host))) {
                throw new PermanentDownloadException("Untrusted mosh redirect: " + uri.getScheme() + "://" + host);
            }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing mosh redirect location");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) {
                    if (status == 408 || status == 429 || status >= 500) {
                        throw new IOException("Transient mosh HTTP status " + status);
                    }
                    throw new PermanentDownloadException("Mosh HTTP status " + status);
                }
                long expected = connection.getContentLengthLong();
                if (expected > MAX_ARCHIVE_BYTES) throw new PermanentDownloadException("Mosh ZIP too large");
                long size = 0;
                try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        size += count;
                        if (size > MAX_ARCHIVE_BYTES) throw new IOException("Mosh ZIP size limit exceeded");
                        output.write(buffer, 0, count);
                    }
                }
                if (expected >= 0 && size != expected) throw new IOException("Incomplete mosh HTTP response");
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new PermanentDownloadException("Too many mosh redirects");
    }

    private static void commit(Path staging, Path output, Move move) throws IOException {
        Path backup = output.resolveSibling(staging.getFileName() + "-backup");
        boolean previous = Files.exists(output);
        if (previous) move.move(output, backup);
        try {
            move.move(staging, output);
        } catch (IOException failure) {
            if (previous) {
                try {
                    move.move(backup, output);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            throw failure;
        }
        deleteTree(backup);
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static final class PermanentDownloadException extends IOException {
        PermanentDownloadException(String message) {
            super(message);
        }
    }
}
