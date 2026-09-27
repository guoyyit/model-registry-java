package com.github.guoyyit.modelregistryjava.registry;

import com.github.guoyyit.modelregistryjava.registry.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.registry.storage.AtomicFiles;
import com.github.guoyyit.modelregistryjava.registry.storage.JsonCodec;
import com.github.guoyyit.modelregistryjava.registry.storage.Journal;
import com.github.guoyyit.modelregistryjava.registry.storage.MessageDigestChecksum;
import com.github.guoyyit.modelregistryjava.registry.storage.PathLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    @TempDir
    Path tempDir;

    private RegistryService registry;

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        RegistryProperties props = new RegistryProperties();
        props.setStoragePath(tempDir.resolve("registry").toString());
        JsonCodec json = new JsonCodec();
        AtomicFiles atomicFiles = new AtomicFiles();
        PathLayout paths = new PathLayout(props);
        Journal journal = new Journal(paths, json, atomicFiles);
        MessageDigestChecksum checksum = new MessageDigestChecksum(props.getChecksumAlgorithm());
        registry = new RegistryService(props, paths, json, atomicFiles, journal, checksum);
        registry.initStorage();
    }

    private VersionMetadata publish(String model, String version, byte[] data, String token) {
        UploadMarker marker = registry.beginUpload(model, version, token, sha256(data),
                "application/octet-stream", (long) data.length);
        try (InputStream in = new ByteArrayInputStream(data)) {
            registry.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry.completeUpload(marker.uploadId());
    }

    @Test
    void concurrentDistinctVersionsAllBecomeVisible() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                await(start);
                byte[] data = ("content-" + idx).getBytes(StandardCharsets.UTF_8);
                publish("model-x", "1.0." + idx, data, "token-" + idx);
                ok.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(n, ok.get());
        assertEquals(n, registry.listVersions("model-x").size());
    }

    @Test
    void concurrentSameVersionUploadYieldsExactlyOneSuccess() throws Exception {
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                await(start);
                try {
                    // same model+version, distinct tokens -> only the first wins
                    publish("model-y", "9.9.9",
                            ("same-" + idx).getBytes(StandardCharsets.UTF_8), "t-" + idx);
                    success.incrementAndGet();
                } catch (RuntimeException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, success.get(), "exactly one upload must win");
        assertEquals(n - 1, rejected.get());
        assertEquals(1, registry.listVersions("model-y").size());
    }

    @Test
    void concurrentSameClientTokenProducesOneVersion() throws Exception {
        // One submission completes first and becomes the single effective version.
        publish("model-z", "7.7.7",
                "identical".getBytes(StandardCharsets.UTF_8), "shared-token");

        // Repeated (concurrent) submissions carrying the same client token must
        // never create a second effective version.
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                await(start);
                try {
                    registry.beginUpload("model-z", "7.7.7", "shared-token",
                            sha256("identical".getBytes(StandardCharsets.UTF_8)),
                            "application/octet-stream", 9L);
                    unexpected.incrementAndGet();
                } catch (RuntimeException e) {
                    if (e.getClass().getSimpleName().equals("DuplicateSubmissionException")
                            || e.getClass().getSimpleName().equals("VersionTakenException")
                            || e.getClass().getSimpleName().equals("VersionAlreadyExistsException")) {
                        duplicates.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, unexpected.get());
        assertEquals(n, duplicates.get());
        assertEquals(1, registry.listVersions("model-z").size());
    }

    @Test
    void concurrentRollbackAndReleaseEndsInExactlyOneDeterministicProduction() throws Exception {
        // establish v1 production and a v2 candidate
        publish("model-r", "1.0.0", "v1".getBytes(StandardCharsets.UTF_8), null);
        publish("model-r", "2.0.0", "v2".getBytes(StandardCharsets.UTF_8), null);
        registry.promoteToCandidate("model-r", "1.0.0");
        registry.promoteToProduction("model-r", "1.0.0");
        registry.promoteToCandidate("model-r", "2.0.0");

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rejectedByState = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final boolean doRollback = i % 2 == 0;
            pool.submit(() -> {
                await(start);
                try {
                    if (doRollback) {
                        registry.rollback("model-r", "1.0.0", "concurrent");
                    } else {
                        registry.promoteToProduction("model-r", "2.0.0");
                    }
                    committed.incrementAndGet();
                } catch (RuntimeException e) {
                    // once the contender has been RETIRED, re-promoting it is a
                    // legitimate, deterministic rejection of a stale request
                    if (e.getClass().getSimpleName().equals("InvalidStageTransitionException")) {
                        rejectedByState.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, unexpected.get(), "no non-deterministic failures allowed");
        assertEquals(n, committed.get() + rejectedByState.get());

        // invariant: exactly one production version, and the journal's final
        // production decision is what readers observe
        long productionCount = registry.listVersions("model-r").stream()
                .filter(v -> v.state() == VersionState.PRODUCTION).count();
        assertEquals(1, productionCount);
        String finalDecisionVersion = registry.auditTrail("model-r").stream()
                .filter(e -> RegistryService.ACT_ROLLBACK.equals(e.action())
                        || RegistryService.ACT_PROMOTE_PRODUCTION.equals(e.action()))
                .reduce((a, b) -> b).orElseThrow().version();
        assertEquals(finalDecisionVersion, registry.getProduction("model-r").version());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
