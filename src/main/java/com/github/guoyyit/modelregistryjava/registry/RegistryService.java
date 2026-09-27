package com.github.guoyyit.modelregistryjava.registry;

import com.github.guoyyit.modelregistryjava.registry.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.registry.exception.ChecksumMismatchException;
import com.github.guoyyit.modelregistryjava.registry.exception.DuplicateSubmissionException;
import com.github.guoyyit.modelregistryjava.registry.exception.IllegalDeleteException;
import com.github.guoyyit.modelregistryjava.registry.exception.InvalidRequestException;
import com.github.guoyyit.modelregistryjava.registry.exception.InvalidStageTransitionException;
import com.github.guoyyit.modelregistryjava.registry.exception.UploadNotFoundException;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionAlreadyExistsException;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionDeactivatedException;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionNotFoundException;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionTakenException;
import com.github.guoyyit.modelregistryjava.registry.storage.AtomicFiles;
import com.github.guoyyit.modelregistryjava.registry.storage.ChecksumStrategy;
import com.github.guoyyit.modelregistryjava.registry.storage.JsonCodec;
import com.github.guoyyit.modelregistryjava.registry.storage.Journal;
import com.github.guoyyit.modelregistryjava.registry.storage.PathLayout;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Core model-registry service.
 *
 * <h2>Concurrency model</h2>
 * All decisions for one model (publish completion, stage transitions, rollback,
 * retire, delete) run behind a single per-model lock, so concurrent release and
 * rollback requests are serialized and their outcome is exactly the order in
 * which they acquired the lock. Different models use different locks. Raw
 * content appends use per-upload locks. Crash recovery takes a global lock.
 */
@Service
public class RegistryService {

    public static final String ACT_PUBLISH = "PUBLISH";
    public static final String ACT_REJECT_UPLOAD = "REJECT_UPLOAD";
    public static final String ACT_PROMOTE_CANDIDATE = "PROMOTE_CANDIDATE";
    public static final String ACT_PROMOTE_PRODUCTION = "PROMOTE_PRODUCTION";
    public static final String ACT_ROLLBACK = "ROLLBACK";
    public static final String ACT_RETIRE = "RETIRE";
    public static final String ACT_DELETE = "DELETE";
    public static final String ACT_RECOVER = "RECOVER";

    private final RegistryProperties properties;
    private final PathLayout paths;
    private final JsonCodec json;
    private final AtomicFiles atomicFiles;
    private final Journal journal;
    private final ChecksumStrategy checksum;

    private final Map<String, Object> modelLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> uploadLocks = new ConcurrentHashMap<>();
    private final Object recoveryLock = new Object();

    public RegistryService(RegistryProperties properties, PathLayout paths, JsonCodec json,
                           AtomicFiles atomicFiles, Journal journal, ChecksumStrategy checksum) {
        this.properties = properties;
        this.paths = paths;
        this.json = json;
        this.atomicFiles = atomicFiles;
        this.journal = journal;
        this.checksum = checksum;
    }

    @PostConstruct
    public void initStorage() {
        try {
            atomicFiles.ensureDirectory(paths.root());
            atomicFiles.ensureDirectory(paths.versionsRoot());
            atomicFiles.ensureDirectory(paths.uploadsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialize storage at " + paths.root(), e);
        }
    }

    private Object modelLock(String model) {
        return modelLocks.computeIfAbsent(paths.safeModel(model), k -> new Object());
    }

    private Object uploadLock(String uploadId) {
        return uploadLocks.computeIfAbsent(paths.safeUploadId(uploadId), k -> new Object());
    }

    private long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // upload / publish
    // ------------------------------------------------------------------

    public UploadMarker beginUpload(String model, String version, String clientToken,
                                    String expectedChecksum, String contentType,
                                    Long expectedSize) {
        String safeModel = paths.safeModel(model);
        String safeVersion = paths.safeVersion(version);
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            throw new InvalidRequestException("expectedChecksum is required ("
                    + checksum.algorithm() + ")");
        }
        if (expectedSize != null && expectedSize < 0) {
            throw new InvalidRequestException("expectedSize must be non-negative");
        }
        synchronized (modelLock(safeModel)) {
            // Repeated submission with the same client token is idempotent:
            // it can never create a second effective version.
            if (clientToken != null && !clientToken.isBlank()) {
                Optional<VersionMetadata> published = findPublishedByToken(safeModel, clientToken);
                if (published.isPresent()) {
                    throw new DuplicateSubmissionException(clientToken,
                            Optional.of(published.get().version()));
                }
                Optional<UploadMarker> existing = findUploadByToken(safeModel, clientToken);
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
            if (isTombstoned(safeModel, safeVersion)) {
                throw new VersionTakenException(safeModel, safeVersion);
            }
            if (Files.exists(paths.metaFile(safeModel, safeVersion))) {
                throw new VersionAlreadyExistsException(safeModel, safeVersion);
            }
            Optional<UploadMarker> inProgress = findUploadForVersion(safeModel, safeVersion);
            if (inProgress.isPresent()) {
                throw new VersionTakenException(safeModel, safeVersion);
            }

            String uploadId = UUID.randomUUID().toString();
            long t = now();
            UploadMarker marker = new UploadMarker(uploadId, safeModel, safeVersion,
                    clientToken, checksum.algorithm(), expectedChecksum.trim().toLowerCase(),
                    contentType == null ? "application/octet-stream" : contentType,
                    expectedSize == null ? -1L : expectedSize,
                    UploadState.UPLOADING, t, t);
            persistMarker(marker);
            try {
                atomicFiles.ensureDirectory(paths.uploadsDir());
                Path bin = paths.uploadContent(uploadId);
                if (!Files.exists(bin)) {
                    Files.createFile(bin);
                }
                atomicFiles.fsyncDirectory(paths.uploadsDir());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create upload content file", e);
            }
            return marker;
        }
    }

    /** Append a chunk of content bytes to an open upload. */
    public void appendUpload(String uploadId, InputStream chunk) {
        String safeId = paths.safeUploadId(uploadId);
        synchronized (uploadLock(safeId)) {
            UploadMarker marker = readMarker(safeId);
            if (marker.state() != UploadState.UPLOADING) {
                throw new InvalidRequestException(
                        "Upload is not accepting content: " + safeId + " state=" + marker.state());
            }
            Path bin = paths.uploadContent(safeId);
            try {
                long before = Files.exists(bin) ? Files.size(bin) : 0L;
                long copied;
                try (OutputStream out = Files.newOutputStream(bin,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.APPEND)) {
                    copied = chunk.transferTo(out);
                }
                long after = before + copied;
                if (marker.expectedSize() >= 0 && after > marker.expectedSize()) {
                    throw new InvalidRequestException("Uploaded content exceeds expectedSize "
                            + marker.expectedSize() + " (got " + after + " bytes)");
                }
                persistMarker(marker.withState(UploadState.UPLOADING, now()));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to append upload content", e);
            }
        }
    }

    /** Verify checksum and publish the version atomically. */
    public VersionMetadata completeUpload(String uploadId) {
        String safeId = paths.safeUploadId(uploadId);
        UploadMarker marker = readMarker(safeId);
        synchronized (modelLock(marker.model())) {
            // re-read under the model lock; recover() may have resolved it
            marker = readMarkerIfExists(safeId)
                    .orElseThrow(() -> new UploadNotFoundException(safeId));
            if (marker.state() == UploadState.PUBLISHED
                    || Files.exists(paths.metaFile(marker.model(), marker.version()))) {
                return getVersion(marker.model(), marker.version());
            }
            if (marker.state() != UploadState.UPLOADING
                    && marker.state() != UploadState.FINALIZING) {
                throw new InvalidRequestException("Cannot complete upload in state " + marker.state());
            }
            Path bin = paths.uploadContent(safeId);
            Verification verification = verifyUpload(marker, bin);

            // From this point the decision to publish is durable.
            persistMarker(marker.withState(UploadState.FINALIZING, now()));
            journal.append(now(), ACT_PUBLISH, marker.model(), marker.version(),
                    "checksum=" + marker.checksumAlg() + ":" + marker.expectedChecksum()
                            + " size=" + verification.size() + " uploadId=" + safeId);
            return commitPublished(marker, bin, verification);
        }
    }

    public UploadMarker getUpload(String uploadId) {
        return readMarker(paths.safeUploadId(uploadId));
    }

    private record Verification(String checksum, long size) {
    }

    private Verification verifyUpload(UploadMarker marker, Path bin) {
        long size;
        String actual;
        try {
            if (!Files.exists(bin)) {
                throw new InvalidRequestException("Upload content is missing: " + marker.uploadId());
            }
            size = Files.size(bin);
            try (InputStream in = Files.newInputStream(bin)) {
                actual = checksum.computeHex(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to verify upload content", e);
        }
        if (marker.expectedSize() >= 0 && size != marker.expectedSize()) {
            rejectUpload(marker, "declared size " + marker.expectedSize() + ", actual " + size);
            throw new InvalidRequestException("Incomplete upload: declared size "
                    + marker.expectedSize() + ", actual " + size);
        }
        if (!actual.equalsIgnoreCase(marker.expectedChecksum())) {
            rejectUpload(marker, "expected " + marker.expectedChecksum() + ", actual " + actual);
            throw new ChecksumMismatchException(marker.expectedChecksum(), actual);
        }
        return new Verification(actual.toLowerCase(), size);
    }

    private void rejectUpload(UploadMarker marker, String reason) {
        journal.append(now(), ACT_REJECT_UPLOAD, marker.model(), marker.version(),
                "uploadId=" + marker.uploadId() + " " + reason);
        try {
            Files.deleteIfExists(paths.uploadContent(marker.uploadId()));
            Files.deleteIfExists(paths.uploadMarker(marker.uploadId()));
            atomicFiles.fsyncDirectory(paths.uploadsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean rejected upload", e);
        }
    }

    private VersionMetadata commitPublished(UploadMarker marker, Path sourceBin,
                                            Verification verification) {
        long t = now();
        VersionMetadata meta = new VersionMetadata(
                marker.model(), marker.version(), marker.checksumAlg(),
                marker.expectedChecksum(), verification.size(), marker.contentType(),
                marker.clientToken(), marker.createdAt(), VersionState.REGISTERED, t);
        try {
            Path versionDir = paths.versionDir(marker.model(), marker.version());
            atomicFiles.ensureDirectory(versionDir);
            Path target = paths.contentFile(marker.model(), marker.version());
            if (!Files.exists(target)) {
                try {
                    Files.move(sourceBin, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(sourceBin, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // meta.json is the commit point: its atomic appearance is the only
            // way a version becomes visible to readers.
            atomicFiles.writeAtomically(paths.metaFile(marker.model(), marker.version()),
                    json.writeBytes(meta));
            Files.deleteIfExists(paths.uploadMarker(marker.uploadId()));
            Files.deleteIfExists(sourceBin);
            atomicFiles.fsyncDirectory(paths.uploadsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to commit published version", e);
        }
        return meta;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    public VersionMetadata getVersion(String model, String version) {
        String safeModel = paths.safeModel(model);
        String safeVersion = paths.safeVersion(version);
        return readMeta(safeModel, safeVersion);
    }

    public InputStream readContent(String model, String version) {
        VersionMetadata meta = getVersion(model, version);
        Path content = paths.contentFile(meta.model(), meta.version());
        if (!Files.exists(content)) {
            // content is only removed for retired versions (configurable retention)
            throw new VersionDeactivatedException(meta.model(), meta.version());
        }
        try {
            return Files.newInputStream(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open content", e);
        }
    }

    public List<VersionMetadata> listVersions(String model) {
        String safeModel = paths.safeModel(model);
        List<VersionMetadata> result = new ArrayList<>();
        Path modelDir = paths.modelDir(safeModel);
        Path versionsDir = modelDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }
        try (Stream<Path> dirs = Files.list(versionsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path metaFile = dir.resolve("meta.json");
                if (Files.exists(metaFile)) {
                    result.add(readMetaFile(metaFile));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list versions", e);
        }
        result.sort(Comparator.comparingLong(VersionMetadata::createdAt).reversed());
        return result;
    }

    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        if (!Files.isDirectory(paths.versionsRoot())) {
            return models;
        }
        try (Stream<Path> dirs = Files.list(paths.versionsRoot())) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                if (Files.isDirectory(d.resolve("versions"))) {
                    models.add(d.getFileName().toString());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list models", e);
        }
        models.sort(String::compareTo);
        return models;
    }

    public VersionMetadata getProduction(String model) {
        String safeModel = paths.safeModel(model);
        return listVersions(safeModel).stream()
                .filter(m -> m.state() == VersionState.PRODUCTION)
                .findFirst()
                .orElseThrow(() -> new VersionNotFoundException(safeModel, "<production>"));
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public VersionMetadata promoteToCandidate(String model, String version) {
        String safeModel = paths.safeModel(model);
        String safeVersion = paths.safeVersion(version);
        synchronized (modelLock(safeModel)) {
            VersionMetadata meta = readMeta(safeModel, safeVersion);
            if (meta.state() != VersionState.REGISTERED) {
                throw new InvalidStageTransitionException(
                        "Only REGISTERED versions can become CANDIDATE, current=" + meta.state());
            }
            journal.append(now(), ACT_PROMOTE_CANDIDATE, safeModel, safeVersion, "");
            return updateState(meta, VersionState.CANDIDATE);
        }
    }

    public VersionMetadata promoteToProduction(String model, String version) {
        String safeModel = paths.safeModel(model);
        String safeVersion = paths.safeVersion(version);
        synchronized (modelLock(safeModel)) {
            VersionMetadata target = readMeta(safeModel, safeVersion);
            // skipping stages is rejected: REGISTERED -> PRODUCTION is not allowed
            if (target.state() != VersionState.CANDIDATE) {
                throw new InvalidStageTransitionException(
                        "Only CANDIDATE versions can be promoted to PRODUCTION, current="
                                + target.state());
            }
            currentProduction(safeModel).ifPresent(current -> {
                journal.append(now(), ACT_RETIRE, safeModel, current.version(),
                        "superseded by promotion of " + safeVersion);
                updateState(current, VersionState.RETIRED);
            });
            journal.append(now(), ACT_PROMOTE_PRODUCTION, safeModel, safeVersion, "");
            return updateState(target, VersionState.PRODUCTION);
        }
    }

    public VersionMetadata retire(String model, String version, String reason) {
        String safeModel = paths.safeModel(model);
        String safeVersion = paths.safeVersion(version);
        synchronized (modelLock(safeModel)) {
            VersionMetadata meta = readMeta(safeModel, safeVersion);
            if (meta.state() == VersionState.RETIRED) {
                throw new InvalidStageTransitionException("Version is already RETIRED");
            }
            journal.append(now(), ACT_RETIRE, safeModel, safeVersion,
                    reason == null ? "" : reason);
            VersionMetadata retired = updateState(meta, VersionState.RETIRED);
            if (!properties.isRetainRetiredContent()) {
                try {
                    Files.deleteIfExists(paths.contentFile(safeModel, safeVersion));
                    atomicFiles.fsyncDirectory(paths.versionDir(safeModel, safeVersion));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to reclaim retired content", e);
                }
            }
            return retired;
        }
    }

    public VersionMetadata rollback(String model, String targetVersion, String reason) {
        String safeModel = paths.safeModel(model);
        String safeTarget = paths.safeVersion(targetVersion);
        synchronized (modelLock(safeModel)) {
            VersionMetadata target = readMeta(safeModel, safeTarget);
            Optional<VersionMetadata> current = currentProduction(safeModel);
            if (current.isPresent() && current.get().version().equals(safeTarget)) {
                return current.get(); // already production: deterministic no-op
            }
            if (current.isPresent()) {
                journal.append(now(), ACT_RETIRE, safeModel, current.get().version(),
                        "superseded by rollback to " + safeTarget);
                updateState(current.get(), VersionState.RETIRED);
            }
            journal.append(now(), ACT_ROLLBACK, safeModel, safeTarget,
                    "from=" + current.map(VersionMetadata::version).orElse("<none>")
                            + " reason=" + (reason == null ? "" : reason));
            // explicit rollback is the only operation that can restore a
            // retired historical version to PRODUCTION
            return updateState(target, VersionState.PRODUCTION);
        }
    }

    public void deleteVersion(String model, String version) {
        String safeModel = paths.safeModel(model);
        String safeVersion = paths.safeVersion(version);
        synchronized (modelLock(safeModel)) {
            VersionMetadata meta = readMeta(safeModel, safeVersion);
            if (meta.state() == VersionState.PRODUCTION) {
                throw new IllegalDeleteException(
                        "A PRODUCTION version cannot be deleted; retire it first");
            }
            if (meta.state() == VersionState.RETIRED) {
                throw new IllegalDeleteException(
                        "A RETIRED version is retained for audit and cannot be deleted");
            }
            boolean everProduced = journal.readAll().stream()
                    .anyMatch(e -> safeModel.equals(e.model()) && safeVersion.equals(e.version())
                            && (ACT_PROMOTE_PRODUCTION.equals(e.action())
                            || ACT_ROLLBACK.equals(e.action())));
            if (everProduced) {
                throw new IllegalDeleteException(
                        "A version that has served PRODUCTION cannot be deleted; retire it");
            }
            journal.append(now(), ACT_DELETE, safeModel, safeVersion, "");
            try {
                Path dir = paths.versionDir(safeModel, safeVersion);
                Files.deleteIfExists(paths.contentFile(safeModel, safeVersion));
                Files.deleteIfExists(paths.metaFile(safeModel, safeVersion));
                Files.deleteIfExists(dir);
                writeTombstone(safeModel, safeVersion);
                atomicFiles.fsyncDirectory(paths.modelDir(safeModel).resolve("versions"));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete version", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // audit / recovery
    // ------------------------------------------------------------------

    public List<AuditEvent> auditTrail(String model) {
        String safeModel = paths.safeModel(model);
        return journal.readAll().stream().filter(e -> safeModel.equals(e.model())).toList();
    }

    public List<AuditEvent> fullAuditTrail() {
        return journal.readAll();
    }

    public RecoveryReport recover() {
        synchronized (recoveryLock) {
            initStorage();
            List<RecoveryReport.ResolvedUpload> completed = new ArrayList<>();
            List<RecoveryReport.ResolvedUpload> reclaimed = new ArrayList<>();
            List<RecoveryReport.ResolvedUpload> suspicious = new ArrayList<>();
            try (Stream<Path> markers = Files.list(paths.uploadsDir())) {
                List<Path> markerFiles = markers
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .toList();
                for (Path markerFile : markerFiles) {
                    UploadMarker marker;
                    try {
                        marker = json.read(Files.readAllBytes(markerFile), UploadMarker.class);
                    } catch (IOException | UncheckedIOException corrupt) {
                        String id = markerFile.getFileName().toString().replace(".json", "");
                        suspicious.add(new RecoveryReport.ResolvedUpload(id, "?", "?",
                                "corrupt marker"));
                        continue;
                    }
                    resolveInterruptedUpload(marker, completed, reclaimed, suspicious);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Recovery sweep failed", e);
            }
            sweepOrphanVersionDirs();
            reconcileProductionStates();
            return new RecoveryReport(completed, reclaimed, suspicious);
        }
    }

    private void resolveInterruptedUpload(UploadMarker marker,
                                          List<RecoveryReport.ResolvedUpload> completed,
                                          List<RecoveryReport.ResolvedUpload> reclaimed,
                                          List<RecoveryReport.ResolvedUpload> suspicious) {
        String id = marker.uploadId();
        try {
            if (Files.exists(paths.metaFile(marker.model(), marker.version()))) {
                // publication committed; only upload residue is left
                Files.deleteIfExists(paths.uploadMarker(id));
                Files.deleteIfExists(paths.uploadContent(id));
                completed.add(new RecoveryReport.ResolvedUpload(id, marker.model(),
                        marker.version(), "already published; removed residue"));
                return;
            }
            Path bin = paths.uploadContent(id);
            Path staged = paths.contentFile(marker.model(), marker.version());
            Path usable = Files.exists(bin) ? bin : (Files.exists(staged) ? staged : null);
            if (usable == null) {
                handleIncomplete(marker, "no content bytes found", reclaimed, suspicious);
                return;
            }
            long size = Files.size(usable);
            boolean sizeOk = marker.expectedSize() < 0 || size == marker.expectedSize();
            String actual;
            try (InputStream in = Files.newInputStream(usable)) {
                actual = checksum.computeHex(in);
            }
            boolean checksumOk = actual.equalsIgnoreCase(marker.expectedChecksum());
            if (!sizeOk || !checksumOk) {
                handleIncomplete(marker,
                        "invalid content (sizeOk=" + sizeOk + ", checksumOk=" + checksumOk + ")",
                        reclaimed, suspicious);
                return;
            }
            // Content is whole: deterministically finalize as a full publish.
            Verification verification = new Verification(actual.toLowerCase(), size);
            VersionMetadata meta = commitPublished(marker, bin, verification);
            journal.append(now(), ACT_RECOVER, marker.model(), marker.version(),
                    "finalized interrupted upload " + id + " as " + meta.state());
            completed.add(new RecoveryReport.ResolvedUpload(id, marker.model(),
                    marker.version(), "finalized after crash"));
        } catch (IOException e) {
            suspicious.add(new RecoveryReport.ResolvedUpload(id, marker.model(),
                    marker.version(), "I/O error: " + e.getMessage()));
        }
    }

    private void handleIncomplete(UploadMarker marker, String reason,
                                  List<RecoveryReport.ResolvedUpload> reclaimed,
                                  List<RecoveryReport.ResolvedUpload> suspicious) {
        journal.append(now(), ACT_REJECT_UPLOAD, marker.model(), marker.version(),
                "recovery: uploadId=" + marker.uploadId() + " " + reason);
        if (properties.isReclaimIncompleteUploads()) {
            try {
                Files.deleteIfExists(paths.uploadContent(marker.uploadId()));
                Files.deleteIfExists(paths.uploadMarker(marker.uploadId()));
                Files.deleteIfExists(paths.contentFile(marker.model(), marker.version()));
                atomicFiles.fsyncDirectory(paths.uploadsDir());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to reclaim interrupted upload", e);
            }
            reclaimed.add(new RecoveryReport.ResolvedUpload(marker.uploadId(),
                    marker.model(), marker.version(), reason + "; storage reclaimed"));
        } else {
            suspicious.add(new RecoveryReport.ResolvedUpload(marker.uploadId(),
                    marker.model(), marker.version(),
                    reason + "; left on disk (registry.reclaim-incomplete-uploads=false)"));
        }
    }

    private void sweepOrphanVersionDirs() {
        if (!Files.isDirectory(paths.versionsRoot())) {
            return;
        }
        try (Stream<Path> models = Files.list(paths.versionsRoot())) {
            models.filter(Files::isDirectory).forEach(modelDir -> {
                Path versionsDir = modelDir.resolve("versions");
                if (!Files.isDirectory(versionsDir)) {
                    return;
                }
                try (Stream<Path> versionDirs = Files.list(versionsDir)) {
                    versionDirs.filter(Files::isDirectory).forEach(versionDir -> {
                        if (!Files.exists(versionDir.resolve("meta.json"))
                                && !Files.exists(versionDir.resolve("deleted.flag"))) {
                            deleteQuietly(versionDir);
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException("Orphan sweep failed", e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Orphan sweep failed", e);
        }
    }

    /**
     * Reconcile PRODUCTION state from the durable journal after a crash that
     * could have torn the two meta updates of a promotion/rollback.
     */
    private void reconcileProductionStates() {
        for (String safeModel : listModelsQuiet()) {
            synchronized (modelLock(safeModel)) {
                List<VersionMetadata> all = readAllMeta(safeModel);
                Optional<AuditEvent> lastDecision = journal.readAll().stream()
                        .filter(e -> safeModel.equals(e.model()))
                        .filter(e -> ACT_PROMOTE_PRODUCTION.equals(e.action())
                                || ACT_ROLLBACK.equals(e.action()))
                        .reduce((a, b) -> b);
                String desired = lastDecision.map(AuditEvent::version).orElse(null);
                for (VersionMetadata meta : all) {
                    boolean shouldBeProduction = meta.version().equals(desired);
                    if (shouldBeProduction && meta.state() != VersionState.PRODUCTION) {
                        updateState(meta, VersionState.PRODUCTION);
                    } else if (!shouldBeProduction && meta.state() == VersionState.PRODUCTION) {
                        updateState(meta, VersionState.RETIRED);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // internal helpers
    // ------------------------------------------------------------------

    private VersionMetadata readMeta(String safeModel, String safeVersion) {
        Path metaFile = paths.metaFile(safeModel, safeVersion);
        if (isTombstoned(safeModel, safeVersion) || !Files.exists(metaFile)) {
            throw new VersionNotFoundException(safeModel, safeVersion);
        }
        return readMetaFile(metaFile);
    }

    private VersionMetadata readMetaFile(Path metaFile) {
        try {
            return json.read(Files.readAllBytes(metaFile), VersionMetadata.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read metadata " + metaFile, e);
        }
    }

    private List<VersionMetadata> readAllMeta(String safeModel) {
        List<VersionMetadata> result = new ArrayList<>();
        Path versionsDir = paths.modelDir(safeModel).resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }
        try (Stream<Path> dirs = Files.list(versionsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path metaFile = dir.resolve("meta.json");
                if (Files.exists(metaFile)) {
                    result.add(readMetaFile(metaFile));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read metadata", e);
        }
        return result;
    }

    private VersionMetadata updateState(VersionMetadata meta, VersionState newState) {
        VersionMetadata updated = meta.withState(newState, now());
        try {
            atomicFiles.writeAtomically(paths.metaFile(meta.model(), meta.version()),
                    json.writeBytes(updated));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist state", e);
        }
        return updated;
    }

    private Optional<VersionMetadata> currentProduction(String safeModel) {
        return readAllMeta(safeModel).stream()
                .filter(m -> m.state() == VersionState.PRODUCTION)
                .findFirst();
    }

    private void persistMarker(UploadMarker marker) {
        try {
            atomicFiles.ensureDirectory(paths.uploadsDir());
            atomicFiles.writeAtomically(paths.uploadMarker(marker.uploadId()),
                    json.writeBytes(marker));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist upload marker", e);
        }
    }

    private UploadMarker readMarker(String safeId) {
        return readMarkerIfExists(safeId)
                .orElseThrow(() -> new UploadNotFoundException(safeId));
    }

    private Optional<UploadMarker> readMarkerIfExists(String safeId) {
        Path file = paths.uploadMarker(safeId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.read(Files.readAllBytes(file), UploadMarker.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload marker " + safeId, e);
        }
    }

    private List<UploadMarker> allMarkers() {
        List<UploadMarker> markers = new ArrayList<>();
        if (!Files.isDirectory(paths.uploadsDir())) {
            return markers;
        }
        try (Stream<Path> files = Files.list(paths.uploadsDir())) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    markers.add(json.read(Files.readAllBytes(p), UploadMarker.class));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read marker " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list upload markers", e);
        }
        return markers;
    }

    private Optional<VersionMetadata> findPublishedByToken(String safeModel, String token) {
        return readAllMeta(safeModel).stream()
                .filter(m -> token.equals(m.clientToken()))
                .findFirst();
    }

    private Optional<UploadMarker> findUploadByToken(String safeModel, String token) {
        return allMarkers().stream()
                .filter(m -> safeModel.equals(m.model()) && token.equals(m.clientToken()))
                .findFirst();
    }

    private Optional<UploadMarker> findUploadForVersion(String safeModel, String safeVersion) {
        return allMarkers().stream()
                .filter(m -> safeModel.equals(m.model()) && safeVersion.equals(m.version()))
                .findFirst();
    }

    private Path tombstoneFile(String safeModel, String safeVersion) {
        return paths.versionDir(safeModel, safeVersion).resolve("deleted.flag");
    }

    private boolean isTombstoned(String safeModel, String safeVersion) {
        return Files.exists(tombstoneFile(safeModel, safeVersion));
    }

    private void writeTombstone(String safeModel, String safeVersion) {
        try {
            Path dir = paths.versionDir(safeModel, safeVersion);
            atomicFiles.ensureDirectory(dir);
            atomicFiles.writeAtomically(tombstoneFile(safeModel, safeVersion),
                    ("deleted at " + now()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write tombstone", e);
        }
    }

    private List<String> listModelsQuiet() {
        return listModels();
    }

    private void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
