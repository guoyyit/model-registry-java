package com.github.guoyyit.modelregistryjava.tests;

import com.github.guoyyit.modelregistryjava.domain.AuditEvent;
import com.github.guoyyit.modelregistryjava.domain.Stage;
import com.github.guoyyit.modelregistryjava.service.ModelRegistryService;
import com.github.guoyyit.modelregistryjava.service.RegisterResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConcurrencyTest {

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void concurrentUploadsGetDistinctVersions(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> versions = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            String content = "weights-concurrent-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    RegisterResult r = svc.register("llm", -1L,
                            LifecycleTest.sha256(content), null, bytes(content));
                    versions.add(r.version().getVersion());
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(List.of(), errors);
        assertEquals(n, versions.size(), "every concurrent upload must get a unique version");
        for (long v : versions) {
            assertNotNull(svc.getVersion("llm", v));
        }
    }

    @Test
    void duplicateSubmissionsProduceSingleEffectiveVersion(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> effectiveVersions = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger reused = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RegisterResult r = svc.register("dup-model", -1L,
                            LifecycleTest.sha256("same-bytes"), null, bytes("same-bytes"));
                    effectiveVersions.add(r.version().getVersion());
                    if (r.reused()) {
                        reused.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, effectiveVersions.size(), "identical content yields one valid version");
        assertEquals(n - 1, reused.get(), "all but the first submission are reported as reused");
        // Default listing exposes just the one published version
        assertEquals(1, svc.listVersions("dup-model", false).size());
    }

    @Test
    void concurrentRollbackAndPromoteHasDeterministicOutcome(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v1 = LifecycleTest.register(svc, "svc-model", "c1").version().getVersion();
        long v2 = LifecycleTest.register(svc, "svc-model", "c2").version().getVersion();
        long v3 = LifecycleTest.register(svc, "svc-model", "c3").version().getVersion();
        svc.promoteToCandidate("svc-model", v1);
        svc.promoteToProduction("svc-model", v1);
        svc.promoteToCandidate("svc-model", v2);
        svc.promoteToCandidate("svc-model", v3);

        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < rounds; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    svc.rollback("svc-model", v1, "concurrent-rollback");
                } catch (Exception ignored) {
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    svc.promoteToProduction("svc-model", v2);
                } catch (Exception ignored) {
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // Exactly one production pointer, and it points at a genuinely PRODUCTION version.
        long productionVersion = svc.getCurrentProduction("svc-model").getVersion();
        assertEquals(Stage.PRODUCTION, svc.getVersion("svc-model", productionVersion).getStage());
        long productionCount = svc.listVersions("svc-model", false).stream()
                .filter(v -> v.getStage() == Stage.PRODUCTION).count();
        assertEquals(1, productionCount, "at most one version is production");

        // Full ordering of the race is preserved in the audit trail.
        List<AuditEvent> audit = svc.getAuditTrail("svc-model");
        Set<Long> seqs = new HashSet<>();
        long lastSeq = 0;
        for (AuditEvent e : audit) {
            assertTrue(e.seq() > lastSeq, "audit events must be totally ordered");
            lastSeq = e.seq();
            assertTrue(seqs.add(e.seq()), "audit sequence numbers must be unique");
        }
        assertTrue(audit.stream().anyMatch(e -> e.type().equals("ROLLBACK")));
    }
}
