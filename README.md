# Local Model Registry & Version Lifecycle Service

A local, single-process model registry built on Spring Boot 3 / Java 17. Models are stored
as **immutable, content-verified versions** that move through a controlled lifecycle
(`REGISTERED -> CANDIDATE -> PRODUCTION`, with explicit `ARCHIVE` / `rollback`). All state
changes are recorded in an append-only, fsynced journal, so the outcome of a publish,
rollback or concurrent upload is always explainable and recoverable after a crash.

---

## 1. Build & run

```bash
mvn spring-boot:run          # starts on http://localhost:8080
mvn test                     # run all automated tests
```

Configurable location and policy (see `src/main/resources/application.properties`):

| Property | Default | Meaning |
| --- | --- | --- |
| `registry.home` | `data/registry` | Root directory holding the journal and blob store |
| `registry.checksum.algorithm` | `sha-256` | Digest algorithm: `sha-256`, `sha-1`, `md5` |
| `registry.duplicate-content` | `REUSE_PUBLISHED` | `REUSE_PUBLISHED` = identical content returns the existing valid version; `REJECT` = reject duplicates with HTTP 409 |
| `registry.recover-on-startup` | `true` | Reclaim crashed-upload staging files and orphaned blobs on boot |

---

## 2. Lifecycle semantics

A version is a point-in-time, immutable record. **Once published (the `REGISTERED` event is
durable) its metadata and content bytes can never change.** Reads only ever return a fully
published version; partial writes are kept in a private staging area and are invisible.

Stages and the only legal transitions:

```
                promote               promote
 REGISTERED ───────────────▶ CANDIDATE ───────────────▶ PRODUCTION
     ▲  ▲                        ▲                         │
     │  │ restore                │  demote (automatic)     │ demote (automatic when a
     │  └────────────── ARCHIVED ◀                         │ newer version is promoted,
     │                              (non-production only)  │ or an explicit rollback runs)
     │                                                     ▼
 FAILED (never published: checksum mismatch / incomplete upload; not readable)
```

* **No stage skipping.** `REGISTERED -> PRODUCTION` directly is rejected with HTTP 409; a
  version must become `CANDIDATE` first.
* **Single production version.** Promoting a candidate demotes the previous production
  version back to `CANDIDATE` atomically (one journaled decision).
* **Backward movement only via explicit rollback.** There is no implicit downgrade other
  than the demotion caused by a newer promotion or an explicit rollback.
* **Rollback targets any historical version.** You may roll back from the current
  production to *any* `REGISTERED`/`CANDIDATE` version (not just the immediate predecessor).
  The result is always a definite historical version: it becomes `PRODUCTION`, the prior
  production becomes `CANDIDATE`, and a `ROLLBACK` event records `fromVersion -> toVersion`
  plus the reason. Rolling back to the already-production version is an idempotent no-op.
  An `ARCHIVED` version cannot be a rollback target until it is `restore`d.
* **Offline / invalidation (`archive`).** A non-production version can be archived. Its
  content is taken offline (content download returns HTTP 410 Gone), but **metadata and the
  full audit trail are retained**. A `PRODUCTION` version can never be deleted or archived
  directly — promote/roll back another version first. `restore` returns an archived version
  to `REGISTERED` so it may be promoted again.
* **Failed uploads never enter the lifecycle.** A checksum mismatch or missing/incomplete
  content marks the attempt `FAILED`; no version with that content becomes readable.

---

## 3. Concurrency & duplicate submissions

* Metadata operations for one model are serialized on a **per-model lock**. Slow byte
  uploads happen outside that lock in a per-upload temp file; the version allocation and
  publication decision are made under the lock.
* **Concurrent uploads receive distinct, monotonically increasing version numbers.**
* A version number is durably reserved when an upload *starts*. An aborted/crashed upload
  keeps its number consumed (it can never be silently overwritten); clients may also supply
  an explicit version, and an already-taken number is rejected with HTTP 409.
* **Identical content is deduplicated.** With `REUSE_PUBLISHED`, N concurrent submissions of
  the same bytes produce exactly one valid version; the other requests return
  `reused=true` pointing at it. Reused attempts are journaled as `UPLOAD_ABORTED` with
  `reason=duplicate-content`, so the decision is auditable.
* A **rollback racing a new publish is serialized** per model. Whichever commits last wins,
  there is still exactly one production pointer, and the full ordering of the race is
  preserved in the monotonically ordered audit trail.

---

## 4. Content verification (checksum) policy

Every registration **must** include `expectedChecksum` (lowercase hex). During completion
the registry streams the staged bytes through the configured `MessageDigest` and compares
the result using a constant-time comparison:

* match → the blob is atomically committed and the version published;
* mismatch → HTTP 422, the attempt is journaled `UPLOAD_FAILED(reason=checksum-mismatch)`,
  staging storage is reclaimed, no version is visible;
* incomplete/missing staged content → HTTP 400 and the same failure path.

The algorithm is configurable via `registry.checksum.algorithm` (`sha-256` recommended;
`sha-1`/`md5` provided for legacy interoperability only). The algorithm used is stored on
every version, so old versions remain self-describing if the configured algorithm changes.

---

## 5. Storage layout & crash recovery / reclamation

```
{registry.home}/
├── journal/
│   └── events.jsonl          append-only, fsynced, one JSON object per line (source of truth)
└── blobs/
    ├── blobs/{xx}/{digest}   immutable content-addressed files (xx = first 2 hex chars)
    └── tmp/*.part            private staging files for in-progress uploads
```

**Publication ordering** is the key to recoverability:

1. bytes stream into an unreferenced `tmp/*.part` file (readers never see it);
2. checksum is verified;
3. the staged file is force-fsynced then **atomically renamed** to its content-addressed
   path (committing the same digest twice is idempotent and never overwrites);
4. only then is the `REGISTERED` event fsynced to the journal and the version made visible.

On startup the registry:

1. replays every complete journal record to rebuild all versions, the production pointers,
   the content-dedup index and the audit trail;
2. **deletes every file left in `tmp/`** — a surviving staging file can only belong to a
   process that died before completing, so its storage is safe to reclaim (the reserved
   version number stays consumed and stays unreadable);
3. **deletes committed blobs not referenced by any journaled version** — these only arise
   from a crash between step 3 and step 4 above;
4. ignores a **torn trailing journal line** (a crash mid-append) because it is not valid JSON;
   only fully written records are ever visible. New events continue from the highest
   complete sequence number + 1.

Therefore after a restart the system contains only **complete publishes, complete
rollbacks, or explicitly marked unfinished operations** (`UPLOAD_ABORTED` / `UPLOAD_FAILED`).
Archived versions keep their journal records forever (audit), while their bytes can be
managed offline; storage for *unfinished* uploads and orphaned pre-journal blobs is
automatically reclaimed.

---

## 6. HTTP API

### Registration

* `POST /api/models/{model}/versions` (multipart `content`, params `expectedChecksum`,
  optional `version`, optional `contentType`) — one-shot verify-and-publish.
* Staged upload for large files:
  * `POST /api/uploads` — JSON `{model, version?, expectedChecksum, contentType}` → `uploadId`
  * `PUT  /api/uploads/{uploadId}/content` (multipart `content`)
  * `POST /api/uploads/{uploadId}/complete` → published version
  * `DELETE /api/uploads/{uploadId}?reason=...` — abort and reclaim storage

### Reads

* `GET /api/models/{model}/versions?includeArchived=false`
* `GET /api/models/{model}/versions/{version}`
* `GET /api/models/{model}/versions/{version}/content` (410 if archived)
* `GET /api/models/{model}/production` and `GET /api/models/{model}/production/content`
* `GET /api/models/{model}/audit` — ordered, immutable event history

### Lifecycle

* `POST /api/models/{model}/versions/{version}/candidate`
* `POST /api/models/{model}/versions/{version}/production`
* `POST /api/models/{model}/rollback/{targetVersion}` — JSON `{reason}`
* `POST /api/models/{model}/versions/{version}/archive` — JSON `{reason}` optional
* `POST /api/models/{model}/versions/{version}/restore`

Error mapping: `404` unknown/non-published version, `409` illegal transition / version
taken / duplicate in `REJECT` mode, `410` archived content, `422` checksum mismatch,
`400` invalid request.

### Quick example

```bash
CS=$(sha256sum model.bin | cut -d' ' -f1)
curl -F "content=@model.bin" \
     "http://localhost:8080/api/models/iris/versions?expectedChecksum=$CS"
# promote
curl -X POST http://localhost:8080/api/models/iris/versions/1/candidate
curl -X POST http://localhost:8080/api/models/iris/versions/1/production
# roll back to a definite historical version
curl -X POST -H 'Content-Type: application/json' -d '{"reason":"incident"}' \
     http://localhost:8080/api/models/iris/rollback/1
```

---

## 7. Tests

`mvn test` covers: configurable checksum verification (match/mismatch/missing), immutable
versions and stage-skip rejection, concurrent upload uniqueness, duplicate-content
deduplication, rollback-vs-publish determinism with an ordered audit trail, rollback to any
historical version, production-deletion protection, archive/restore semantics, and crash
recovery (staging reclamation, failed verification, identical state replay, torn journal
line tolerance, orphan-blob reclamation), plus an end-to-end HTTP integration test.
