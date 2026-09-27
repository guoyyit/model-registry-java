package com.github.guoyyit.modelregistryjava.service;

import com.github.guoyyit.modelregistryjava.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.domain.AuditEvent;
import com.github.guoyyit.modelregistryjava.domain.ModelVersion;
import com.github.guoyyit.modelregistryjava.domain.Stage;
import com.github.guoyyit.modelregistryjava.exception.ConflictException;
import com.github.guoyyit.modelregistryjava.exception.GoneException;
import com.github.guoyyit.modelregistryjava.exception.NotFoundException;
import com.github.guoyyit.modelregistryjava.exception.RegistryException;
import com.github.guoyyit.modelregistryjava.exception.ValidationException;
import com.github.guoyyit.modelregistryjava.storage.BlobStorage;
import com.github.guoyyit.modelregistryjava.storage.EventJournal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core model registry.
 *
 * <p>Concurrency model: all metadata mutations for one model are serialized on a per-model
 * monitor. Content bytes are staged into an unreferenced temp file <em>outside</em> that lock,
 * so slow uploads do not block other operations; the version number and the publication
 * decision are made while holding the lock.
 *
 * <p>Durability model: the append-only journal is the single source of truth. A version only
 * becomes visible after its REGISTERED record is durable; the content blob is committed
 * atomically immediately beforehand. Recovery reconciles the two and reclaims leftovers.
 */
@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);

    static final String E_UPLOAD_STARTED = "UPLOAD_STARTED";
    static final String E_REGISTERED = "REGISTERED";
    static final String E_UPLOAD_ABORTED = "UPLOAD_ABORTED";
    static final String E_UPLOAD_FAILED = "UPLOAD_FAILED";
    static final String E_PROMOTED = "PROMOTED";
    static final String E_DEMOTED = "DEMOTED";
    static final String E_PRODUCTION_SET = "PRODUCTION_SET";
    static final String E_ROLLBACK = "ROLLBACK";
    static final String E_ARCHIVED = "ARCHIVED";
    static final String E_RESTORED = "RESTORED";

    private final RegistryProperties properties;
    private final EventJournal journal;
    private final BlobStorage blobStorage;
    private final ChecksumStrategy checksumStrategy;
    private final ModelRegistryState state = new ModelRegistryState();
    private final ConcurrentHashMap<String, Object> modelLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UploadSession> sessions = new ConcurrentHashMap<>();

    public ModelRegistryService(RegistryProperties properties) {
        this.properties = properties;
        Path home = Path.of(properties.getHome());
        this.journal = new EventJournal(home.resolve("journal/events.jsonl"));
        this.blobStorage = new BlobStorage(home.resolve("blobs"));
        this.checksumStrategy = new DigestChecksumStrategy(properties.getChecksum().getAlgorithm());
    }

    @PostConstruct
    public void recover() {
        try {
            blobStorage.init();
            journal.init();
            replay(journal.readAll());
            if (properties.isRecoverOnStartup()) {
                int tmp = blobStorage.cleanupStagedUploads();
                Set<String> referenced = collectReferencedBlobs();
                int orphanBlobs = blobStorage.cleanupOrphanBlobs(referenced);
                if (tmp > 0 || orphanBlobs > 0) {
                    log.info("Crash recovery reclaimed {} staged upload(s) and {} orphan blob(s)",
                            tmp, orphanBlobs);
                }
            }
        } catch (IOException e) {
            throw new RegistryException("Startup recovery failed", e);
        }
    }

    private Object lockFor(String model) {
        return modelLocks.computeIfAbsent(model, k -> new Object());
    }

    // ============================ registration ============================

    /** One-shot register: stream content, verify checksum, publish immutably. */
    public RegisterResult register(String model, long requestedVersion, String expectedChecksum,
                                   String contentType, InputStream content) {
        Objects.requireNonNull(content, "content");
        UploadSession session = beginUpload(model,
                requestedVersion > 0 ? requestedVersion : null, expectedChecksum, contentType);
        try {
            Files.copy(content, session.getTempFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            failSession(session, "io-error", e.getMessage());
            throw new RegistryException("Failed to stage content", e);
        }
        return completeUpload(session.getUploadId());
    }

    /** Allocate a version and create a private staging area. Idempotent per uploadId. */
    public UploadSession beginUpload(String model, Long requestedVersion, String expectedChecksum,
                                     String contentType) {
        validateModelName(model);
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            throw new ValidationException(
                    "expectedChecksum is required so content is verified before publication");
        }
        synchronized (lockFor(model)) {
            long version;
            if (requestedVersion != null) {
                version = requestedVersion;
                if (version <= 0) {
                    throw new ValidationException("version must be positive");
                }
                ModelVersion existing = state.versionsOf(model).get(version);
                if (existing != null && existing.getStage() != Stage.FAILED) {
                    throw new ConflictException(
                            "Version " + version + " of model '" + model + "' is already taken");
                }
                long last = state.lastAllocated.getOrDefault(model, 0L);
                if (version > last) {
                    state.lastAllocated.put(model, version);
                }
            } else {
                version = state.lastAllocated.getOrDefault(model, 0L) + 1;
                state.lastAllocated.put(model, version);
            }
            BlobStorage.StagedUpload staged = blobStorage.stageUpload(model, version,
                    checksumStrategy.algorithm());
            recordEvent(E_UPLOAD_STARTED, model, version, Map.of(
                    "uploadId", staged.getTempFile().getFileName().toString(),
                    "checksum", expectedChecksum.trim().toLowerCase(),
                    "checksumAlgorithm", checksumStrategy.algorithm(),
                    "contentType", contentType == null ? "" : contentType));
            UploadSession session = new UploadSession(
                    staged.getTempFile().getFileName().toString(), model, version,
                    staged.getTempFile(), expectedChecksum.trim().toLowerCase(), contentType);
            sessions.put(session.getUploadId(), session);
            return session;
        }
    }

    /** Verify staged content and publish exactly one immutable version. */
    public RegisterResult completeUpload(String uploadId) {
        UploadSession session = requireSession(uploadId);
        // Heavy I/O and hashing happen outside the model lock.
        if (!Files.isRegularFile(session.getTempFile())) {
            failSession(session, "incomplete", "staged content is missing or incomplete");
            throw new ValidationException("Upload content is incomplete");
        }
        long size;
        try {
            size = Files.size(session.getTempFile());
            try (InputStream in = Files.newInputStream(session.getTempFile())) {
                checksumStrategy.verify(in, session.getExpectedChecksum());
            }
        } catch (ValidationException e) {
            failSession(session, "checksum-mismatch", e.getMessage());
            throw e;
        } catch (IOException e) {
            failSession(session, "io-error", e.getMessage());
            throw new RegistryException("Failed to verify upload", e);
        }
        String blobKey;
        try {
            BlobStorage.StagedUpload staged = new BlobStorage.StagedUpload(
                    session.getTempFile(), session.getModel(), session.getVersion());
            blobKey = blobStorage.commit(staged, session.getExpectedChecksum());
        } catch (IOException e) {
            throw new RegistryException("Failed to commit content blob", e);
        }

        String model = session.getModel();
        long version = session.getVersion();
        synchronized (lockFor(model)) {
            // Re-check under lock: a duplicate concurrent upload may have published first.
            Long duplicate = state.checksumIndex
                    .getOrDefault(model, Map.of())
                    .get(session.getExpectedChecksum());
            if (duplicate != null) {
                ModelVersion existing = state.versionsOf(model).get(duplicate);
                if (existing != null) {
                    sessions.remove(uploadId);
                    recordEvent(E_UPLOAD_ABORTED, model, version, Map.of(
                            "reason", "duplicate-content",
                            "reusedVersion", duplicate,
                            "blobKey", blobKey));
                    return new RegisterResult(existing, true);
                }
            }
            if (properties.getDuplicateContent() == RegistryProperties.DuplicateContent.REJECT
                    && duplicate != null) {
                sessions.remove(uploadId);
                throw new ConflictException("Identical content already registered");
            }
            // Requested version may have been taken by another upload in the meantime
            // (it cannot have been: allocation was durable, but keep the guard explicit).
            ModelVersion prior = state.versionsOf(model).get(version);
            if (prior != null && prior.getStage() != Stage.FAILED) {
                sessions.remove(uploadId);
                return new RegisterResult(prior, true);
            }
            Instant now = Instant.now();
            ModelVersion mv = new ModelVersion(model, version, Stage.REGISTERED, blobKey,
                    session.getExpectedChecksum(), checksumStrategy.algorithm(), size,
                    emptyToNull(session.getContentType()), now, now, now);
            recordEvent(E_REGISTERED, model, version, payloadFor(mv));
            applyRegistered(mv);
            sessions.remove(uploadId);
            return new RegisterResult(mv, false);
        }
    }

    /** Explicitly give up an upload; its staged storage is reclaimed. */
    public void abortUpload(String uploadId, String reason) {
        UploadSession session = requireSession(uploadId);
        sessions.remove(uploadId);
        blobStorage.abort(new BlobStorage.StagedUpload(
                session.getTempFile(), session.getModel(), session.getVersion()));
        synchronized (lockFor(session.getModel())) {
            recordEvent(E_UPLOAD_ABORTED, session.getModel(), session.getVersion(), Map.of(
                    "reason", reason == null ? "client-abort" : reason));
        }
    }

    private void failSession(UploadSession session, String reason, String detail) {
        sessions.remove(session.getUploadId());
        blobStorage.abort(new BlobStorage.StagedUpload(
                session.getTempFile(), session.getModel(), session.getVersion()));
        synchronized (lockFor(session.getModel())) {
            recordEvent(E_UPLOAD_FAILED, session.getModel(), session.getVersion(), Map.of(
                    "reason", reason,
                    "detail", detail == null ? "" : detail,
                    "checksum", session.getExpectedChecksum()));
        }
    }

    private UploadSession requireSession(String uploadId) {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            throw new NotFoundException("Unknown or already finished upload: " + uploadId);
        }
        return session;
    }

    /** Look up a live staged upload so its temp file can receive content bytes. */
    public UploadSession getUploadSession(String uploadId) {
        return requireSession(uploadId);
    }

    // ============================ reads ============================

    public List<ModelVersion> listVersions(String model, boolean includeArchived) {
        synchronized (lockFor(model)) {
            List<ModelVersion> out = new ArrayList<>();
            for (ModelVersion v : state.versionsOf(model).values()) {
                if (v.getStage() == Stage.FAILED) {
                    continue;
                }
                if (!includeArchived && v.getStage() == Stage.ARCHIVED) {
                    continue;
                }
                out.add(v.snapshot());
            }
            return out;
        }
    }

    public ModelVersion getVersion(String model, long version) {
        synchronized (lockFor(model)) {
            ModelVersion v = state.versionsOf(model).get(version);
            if (v == null || v.getStage() == Stage.FAILED) {
                throw new NotFoundException("No published version " + version
                        + " for model '" + model + "'");
            }
            return v.snapshot();
        }
    }

    public VersionContent readContent(String model, long version) {
        ModelVersion v = getVersion(model, version);
        if (v.getStage() == Stage.ARCHIVED) {
            throw new GoneException("Version " + version + " of model '" + model
                    + "' is archived; metadata and audit history are retained, content is offline");
        }
        return openContent(v);
    }

    public ModelVersion getCurrentProduction(String model) {
        synchronized (lockFor(model)) {
            Long current = state.production.get(model);
            if (current == null) {
                throw new NotFoundException("No production version for model '" + model + "'");
            }
            ModelVersion v = state.versionsOf(model).get(current);
            if (v == null || v.getStage() != Stage.PRODUCTION) {
                throw new NotFoundException("No production version for model '" + model + "'");
            }
            return v.snapshot();
        }
    }

    public VersionContent readProductionContent(String model) {
        return openContent(getCurrentProduction(model));
    }

    public List<AuditEvent> getAuditTrail(String model) {
        synchronized (lockFor(model)) {
            return List.copyOf(state.audit.getOrDefault(model, List.of()));
        }
    }

    private VersionContent openContent(ModelVersion v) {
        try {
            return new VersionContent(v, blobStorage.open(v.getBlobKey()));
        } catch (IOException e) {
            throw new RegistryException("Cannot open content of "
                    + v.getModel() + ":" + v.getVersion(), e);
        }
    }

    // ============================ lifecycle ============================

    /** REGISTERED -> CANDIDATE. Any other transition (including skips) is rejected. */
    public ModelVersion promoteToCandidate(String model, long version) {
        synchronized (lockFor(model)) {
            ModelVersion v = requireLiveVersion(model, version);
            if (v.getStage() != Stage.REGISTERED) {
                throw new ConflictException("Version " + version + " is " + v.getStage()
                        + ", cannot promote to CANDIDATE except from REGISTERED");
            }
            return changeStage(v, Stage.CANDIDATE, E_PROMOTED, "promoted to candidate");
        }
    }

    /** CANDIDATE -> PRODUCTION; the previous production version is demoted to CANDIDATE. */
    public ModelVersion promoteToProduction(String model, long version) {
        synchronized (lockFor(model)) {
            ModelVersion v = requireLiveVersion(model, version);
            if (v.getStage() != Stage.CANDIDATE) {
                throw new ConflictException("Version " + version + " is " + v.getStage()
                        + "; only a CANDIDATE can be promoted to PRODUCTION (no stage skipping)");
            }
            Long previous = state.production.get(model);
            Instant now = Instant.now();
            if (previous != null && previous != version) {
                ModelVersion old = state.versionsOf(model).get(previous);
                if (old != null && old.getStage() == Stage.PRODUCTION) {
                    ModelVersion demoted = old.withStage(Stage.CANDIDATE, now);
                    recordEvent(E_DEMOTED, model, previous, Map.of(
                            "from", Stage.PRODUCTION.name(),
                            "to", Stage.CANDIDATE.name(),
                            "reason", "superseded by " + version));
                    applyVersion(demoted);
                }
            }
            ModelVersion promoted = v.withStage(Stage.PRODUCTION, now);
            recordEvent(E_PRODUCTION_SET, model, version, Map.of(
                    "from", previous == null ? "" : String.valueOf(previous),
                    "action", "promote"));
            applyVersion(promoted);
            state.production.put(model, version);
            return promoted;
        }
    }

    /**
     * Roll the production pointer back to any historical (REGISTERED/CANDIDATE) version.
     * The current production version becomes CANDIDATE; the target becomes PRODUCTION.
     * Rolling back to the version that is already production is an idempotent no-op.
     */
    public ModelVersion rollback(String model, long targetVersion, String reason) {
        synchronized (lockFor(model)) {
            ModelVersion target = requireLiveVersion(model, targetVersion);
            if (target.getStage() == Stage.ARCHIVED) {
                throw new ConflictException("Cannot roll back to archived version "
                        + targetVersion + "; restore it first");
            }
            Long currentVersion = state.production.get(model);
            if (currentVersion != null && currentVersion == targetVersion
                    && target.getStage() == Stage.PRODUCTION) {
                return target;
            }
            if (target.getStage() != Stage.REGISTERED && target.getStage() != Stage.CANDIDATE) {
                throw new ConflictException("Rollback target " + targetVersion + " is "
                        + target.getStage());
            }
            Instant now = Instant.now();
            ModelVersion previous = currentVersion == null ? null
                    : state.versionsOf(model).get(currentVersion);
            if (previous != null && previous.getStage() == Stage.PRODUCTION) {
                ModelVersion demoted = previous.withStage(Stage.CANDIDATE, now);
                recordEvent(E_DEMOTED, model, previous.getVersion(), Map.of(
                        "from", Stage.PRODUCTION.name(),
                        "to", Stage.CANDIDATE.name(),
                        "reason", "rollback to " + targetVersion));
                applyVersion(demoted);
            }
            ModelVersion rolled = target.withStage(Stage.PRODUCTION, now);
            recordEvent(E_ROLLBACK, model, targetVersion, Map.of(
                    "fromVersion", currentVersion == null ? "" : String.valueOf(currentVersion),
                    "toVersion", targetVersion,
                    "reason", reason == null ? "" : reason));
            applyVersion(rolled);
            state.production.put(model, targetVersion);
            return rolled;
        }
    }

    /** Take a non-production version offline. A PRODUCTION version must be demoted first. */
    public ModelVersion archive(String model, long version, String reason) {
        synchronized (lockFor(model)) {
            ModelVersion v = requireLiveVersion(model, version);
            if (v.getStage() == Stage.PRODUCTION) {
                throw new ConflictException("Production version " + version
                        + " cannot be deleted/archived directly; promote or roll back another "
                        + "version first");
            }
            if (v.getStage() == Stage.ARCHIVED) {
                return v;
            }
            return changeStage(v, Stage.ARCHIVED, E_ARCHIVED,
                    reason == null ? "archived" : reason);
        }
    }

    /** Bring an archived version back into the lifecycle at REGISTERED. */
    public ModelVersion restore(String model, long version) {
        synchronized (lockFor(model)) {
            ModelVersion v = state.versionsOf(model).get(version);
            if (v == null) {
                throw new NotFoundException("No version " + version + " for model '" + model + "'");
            }
            if (v.getStage() != Stage.ARCHIVED) {
                throw new ConflictException("Version " + version + " is " + v.getStage()
                        + ", only ARCHIVED versions can be restored");
            }
            return changeStage(v, Stage.REGISTERED, E_RESTORED, "restored from archive");
        }
    }

    private ModelVersion requireLiveVersion(String model, long version) {
        ModelVersion v = state.versionsOf(model).get(version);
        if (v == null || v.getStage() == Stage.FAILED) {
            throw new NotFoundException("No published version " + version
                    + " for model '" + model + "'");
        }
        return v;
    }

    private ModelVersion changeStage(ModelVersion v, Stage to, String eventType, String detail) {
        Instant now = Instant.now();
        ModelVersion updated = v.withStage(to, now);
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "from", v.getStage().name(),
                "to", to.name(),
                "detail", detail));
        recordEvent(eventType, v.getModel(), v.getVersion(), payload);
        applyVersion(updated);
        return updated;
    }

    // ============================ journal replay ============================

    private void replay(List<EventJournal.Record> records) {
        for (EventJournal.Record r : records) {
            Map<String, Object> p = r.payload();
            switch (r.type()) {
                case E_UPLOAD_STARTED -> {
                    bumpAllocation(r.model(), r.version());
                    addAudit(r);
                }
                case E_REGISTERED -> {
                    bumpAllocation(r.model(), r.version());
                    ModelVersion mv = versionFromPayload(r.model(), r.version(), r.at(), p);
                    applyRegistered(mv);
                    addAudit(r);
                }
                case E_UPLOAD_ABORTED, E_UPLOAD_FAILED -> {
                    bumpAllocation(r.model(), r.version());
                    addAudit(r);
                }
                case E_PROMOTED, E_DEMOTED, E_RESTORED -> {
                    Stage to = Stage.valueOf(String.valueOf(p.get("to")));
                    applyStage(r.model(), r.version(), to, r.at());
                    addAudit(r);
                }
                case E_PRODUCTION_SET -> {
                    applyStage(r.model(), r.version(), Stage.PRODUCTION, r.at());
                    demoteOtherProduction(r.model(), r.version(), r.at());
                    state.production.put(r.model(), r.version());
                    addAudit(r);
                }
                case E_ROLLBACK -> {
                    long toVersion = ((Number) p.get("toVersion")).longValue();
                    applyStage(r.model(), r.version(), Stage.PRODUCTION, r.at());
                    demoteOtherProduction(r.model(), toVersion, r.at());
                    state.production.put(r.model(), toVersion);
                    addAudit(r);
                }
                case E_ARCHIVED -> {
                    applyStage(r.model(), r.version(), Stage.ARCHIVED, r.at());
                    removeFromChecksumIndex(r.model(), r.version());
                    addAudit(r);
                }
                default -> log.warn("Unknown journal event type: {}", r.type());
            }
        }
    }

    private void demoteOtherProduction(String model, long keepVersion, Instant at) {
        for (ModelVersion other : state.versionsOf(model).values()) {
            if (other.getVersion() != keepVersion && other.getStage() == Stage.PRODUCTION) {
                applyVersion(other.withStage(Stage.CANDIDATE, at));
            }
        }
    }

    private void applyStage(String model, long version, Stage stage, Instant at) {
        Map<Long, ModelVersion> map = state.versionsOf(model);
        ModelVersion current = map.get(version);
        if (current == null) {
            // Stage event without surviving registration: ignored but allocation stays bumped.
            return;
        }
        applyVersion(current.withStage(stage, at));
    }

    private void applyRegistered(ModelVersion mv) {
        applyVersion(mv);
        state.checksumIndex
                .computeIfAbsent(mv.getModel(), k -> new LinkedHashMap<>())
                .put(mv.getChecksum(), mv.getVersion());
    }

    private void applyVersion(ModelVersion mv) {
        state.versionsOf(mv.getModel()).put(mv.getVersion(), mv);
    }

    private void removeFromChecksumIndex(String model, long version) {
        Map<String, Long> idx = state.checksumIndex.get(model);
        if (idx == null) {
            return;
        }
        idx.values().removeIf(v -> v == version);
    }

    private void bumpAllocation(String model, long version) {
        long last = state.lastAllocated.getOrDefault(model, 0L);
        if (version > last) {
            state.lastAllocated.put(model, version);
        }
    }

    private void addAudit(EventJournal.Record r) {
        String detail = describe(r);
        state.audit.computeIfAbsent(r.model(), k -> new ArrayList<>())
                .add(new AuditEvent(r.seq(), r.model(), r.version(), r.type(), r.at(), detail));
    }

    private String describe(EventJournal.Record r) {
        Map<String, Object> p = r.payload();
        return switch (r.type()) {
            case E_PROMOTED, E_DEMOTED, E_RESTORED, E_ARCHIVED ->
                    p.getOrDefault("from", "?") + "->" + p.getOrDefault("to", "?")
                            + " (" + p.getOrDefault("detail", "") + ")";
            case E_PRODUCTION_SET -> "production -> v" + r.version();
            case E_ROLLBACK -> "rollback " + p.getOrDefault("fromVersion", "")
                    + " -> v" + p.get("toVersion")
                    + (p.get("reason") != null && !String.valueOf(p.get("reason")).isBlank()
                        ? " (" + p.get("reason") + ")" : "");
            case E_UPLOAD_STARTED -> "upload started";
            case E_UPLOAD_ABORTED -> "upload aborted: " + p.getOrDefault("reason", "");
            case E_UPLOAD_FAILED -> "upload failed: " + p.getOrDefault("reason", "");
            case E_REGISTERED -> "version registered";
            default -> r.type();
        };
    }

    @SuppressWarnings("unchecked")
    private ModelVersion versionFromPayload(String model, long version, Instant at,
                                            Map<String, Object> p) {
        return new ModelVersion(
                model,
                version,
                Stage.REGISTERED,
                String.valueOf(p.get("blobKey")),
                String.valueOf(p.get("checksum")),
                String.valueOf(p.getOrDefault("checksumAlgorithm",
                        properties.getChecksum().getAlgorithm())),
                p.get("size") == null ? 0L : ((Number) p.get("size")).longValue(),
                emptyToNull(String.valueOf(p.getOrDefault("contentType", ""))),
                at, at, at);
    }

    private Set<String> collectReferencedBlobs() {
        Set<String> keys = new HashSet<>();
        for (Map<Long, ModelVersion> perModel : state.versions.values()) {
            for (ModelVersion v : perModel.values()) {
                if (v.getBlobKey() != null) {
                    keys.add(v.getBlobKey());
                }
            }
        }
        return keys;
    }

    // ============================ helpers ============================

    private Map<String, Object> payloadFor(ModelVersion mv) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("blobKey", mv.getBlobKey());
        p.put("checksum", mv.getChecksum());
        p.put("checksumAlgorithm", mv.getChecksumAlgorithm());
        p.put("size", mv.getSize());
        p.put("contentType", mv.getContentType() == null ? "" : mv.getContentType());
        return p;
    }

    private long appendEvent(String type, String model, Long version, Map<String, Object> payload) {
        try {
            return journal.append(type, model, version, payload);
        } catch (IOException e) {
            throw new RegistryException("Failed to persist event " + type, e);
        }
    }

    /**
     * Persist an event and reflect it in the in-memory audit index atomically (logical).
     * Caller must hold the per-model lock. The journal timestamp is reused for the audit row
     * so live state and replayed state are identical.
     */
    private long recordEvent(String type, String model, Long version, Map<String, Object> payload) {
        Instant at = Instant.now();
        long seq;
        try {
            seq = journal.append(type, model, version, payload, at);
        } catch (IOException e) {
            throw new RegistryException("Failed to persist event " + type, e);
        }
        EventJournal.Record rec = new EventJournal.Record(seq, type, model, version, at, payload);
        state.audit.computeIfAbsent(model, k -> new ArrayList<>())
                .add(new AuditEvent(seq, model, version, type, at, describe(rec)));
        return seq;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void validateModelName(String model) {
        if (model == null || model.isBlank()) {
            throw new ValidationException("model must not be blank");
        }
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new ValidationException("Invalid model name: " + model);
        }
    }
}
