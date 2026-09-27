package com.github.guoyyit.modelregistryjava.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Filesystem blob store used by the registry.
 *
 * <p>Layout under the root directory:
 * <pre>
 *   blobs/{first2digits}/{digest}     immutable, content-addressed committed files
 *   tmp/{model}-{version}-{uuid}.part private staging files for in-progress uploads
 * </pre>
 *
 * <p>A staged file is invisible to readers. {@link #commit} performs a durable, atomic
 * rename into the content-addressed area; the same digest always maps to the same key, so a
 * retried/duplicated commit is idempotent and never overwrites a different blob.
 */
public class BlobStorage {

    private final Path rootDir;
    private final Path blobsDir;
    private final Path tmpDir;

    public BlobStorage(Path rootDir) {
        this.rootDir = rootDir;
        this.blobsDir = rootDir.resolve("blobs");
        this.tmpDir = rootDir.resolve("tmp");
    }

    public void init() throws IOException {
        Files.createDirectories(blobsDir);
        Files.createDirectories(tmpDir);
    }

    /** Store a new upload in a private, unreferenced temp file. */
    public StagedUpload stageUpload(String model, long version, String suffix) {
        try {
            init();
            String safeModel = model.replaceAll("[^A-Za-z0-9._-]", "_");
            String name = safeModel + "-" + version + "-" + UUID.randomUUID()
                    + (suffix == null || suffix.isBlank() ? ".part" : "-" + suffix + ".part");
            Path tempFile = Files.createFile(tmpDir.resolve(name));
            return new StagedUpload(tempFile, model, version);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot stage upload for " + model + ":" + version, e);
        }
    }

    /**
     * Atomically publish a staged upload under its content digest. Idempotent: committing a
     * digest that already exists simply discards the staged file and returns the existing key.
     */
    public String commit(StagedUpload upload, String hexDigest) throws IOException {
        fsyncFile(upload.getTempFile());
        Path shard = blobsDir.resolve(hexDigest.substring(0, 2));
        Files.createDirectories(shard);
        Path target = shard.resolve(hexDigest);
        if (Files.exists(target)) {
            // Same content already committed (retried upload / duplicate submission).
            Files.deleteIfExists(upload.getTempFile());
            return keyFor(hexDigest);
        }
        try {
            Files.move(upload.getTempFile(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(upload.getTempFile(), target);
        }
        fsyncDirectory(shard);
        fsyncDirectory(blobsDir);
        return keyFor(hexDigest);
    }

    /** Remove a staged upload (explicit abort / failed verification / recovery). */
    public void abort(StagedUpload upload) {
        if (upload == null) {
            return;
        }
        try {
            Files.deleteIfExists(upload.getTempFile());
        } catch (IOException ignored) {
            // best-effort cleanup; startup recovery sweeps everything left in tmp/
        }
    }

    /** Open a committed blob for reading. */
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(pathFor(key));
    }

    /** Delete a committed blob (used only when a commit must be rolled back). */
    public void delete(String key) throws IOException {
        Path p = pathFor(key);
        Files.deleteIfExists(p);
    }

    /**
     * Delete every file under {@code tmp/}. Called during startup recovery: any surviving
     * staging file belongs to a process that died before completing, so it is safe to reclaim.
     */
    public int cleanupStagedUploads() throws IOException {
        init();
        int removed = 0;
        try (Stream<Path> files = Files.list(tmpDir)) {
            for (Path f : files.toList()) {
                if (Files.isRegularFile(f) && Files.deleteIfExists(f)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Delete committed blobs that are not referenced by any recovered journal record.
     * A blob only becomes referenced after its REGISTERED event is durable, so a blob left
     * behind by a crash between commit and journal append is a safe-to-reclaim orphan.
     */
    public int cleanupOrphanBlobs(Set<String> referencedKeys) throws IOException {
        init();
        int[] removed = {0};
        if (!Files.isDirectory(blobsDir)) {
            return 0;
        }
        Files.walkFileTree(blobsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String digest = file.getFileName().toString();
                if (!referencedKeys.contains(keyFor(digest)) && !referencedKeys.contains(digest)) {
                    try {
                        if (Files.deleteIfExists(file)) {
                            removed[0]++;
                        }
                    } catch (IOException ignored) {
                        // leave in place; a later startup can retry
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                try (Stream<Path> entries = Files.list(dir)) {
                    if (dir.equals(blobsDir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return removed[0];
    }

    private static String keyFor(String hexDigest) {
        return hexDigest.substring(0, 2) + "/" + hexDigest;
    }

    private Path pathFor(String key) {
        String normalized = key.contains("/") ? key : key.substring(0, 2) + "/" + key;
        return blobsDir.resolve(normalized).normalize();
    }

    private static void fsyncFile(Path file) throws IOException {
        // Force file contents to disk before the rename that publishes it.
        try (var ch = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // Some filesystems do not support force; the atomic rename still isolates readers.
        }
    }

    private static void fsyncDirectory(Path dir) {
        // Best-effort directory fsync so the rename is recorded in the directory entry.
        // Opening a directory as a channel is unsupported on some platforms/JDKs; in that
        // case the atomic move already guarantees readers never see a half-written file.
        try (var ch = java.nio.channels.FileChannel.open(dir,
                java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // best effort only
        }
    }

    /** Handle to an in-progress upload's temp file. */
    public static final class StagedUpload {
        private final Path tempFile;
        private final String model;
        private final long version;

        public StagedUpload(Path tempFile, String model, long version) {
            this.tempFile = tempFile;
            this.model = model;
            this.version = version;
        }

        public Path getTempFile() { return tempFile; }
        public String getModel() { return model; }
        public long getVersion() { return version; }
    }
}
