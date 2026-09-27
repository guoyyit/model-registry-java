package com.github.guoyyit.modelregistryjava.tests;

import com.github.guoyyit.modelregistryjava.domain.Stage;
import com.github.guoyyit.modelregistryjava.exception.ChecksumMismatchException;
import com.github.guoyyit.modelregistryjava.exception.NotFoundException;
import com.github.guoyyit.modelregistryjava.service.ModelRegistryService;
import com.github.guoyyit.modelregistryjava.service.UploadSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {

    private long countFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void abortedUploadStorageIsReclaimedOnRestart(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        UploadSession session = svc.beginUpload("m", null,
                LifecycleTest.sha256("whatever"), null);
        Files.writeString(session.getTempFile(), "partial-bytes");
        // Simulate a crash: drop the in-memory session, restart the service.
        ModelRegistryService restarted = TestServices.newService(home);
        assertEquals(0, countFiles(home.resolve("blobs/tmp")),
                "staged files of crashed uploads are reclaimed");
        assertThrows(NotFoundException.class,
                () -> restarted.getUploadSession(session.getUploadId()));
        // Version allocation keeps advancing; the half-upload never becomes readable.
        // The started upload durably occupied v1 (it can never be reused/overwritten),
        // so the next publication is v2.
        long v = LifecycleTest.register(restarted, "m", "real-content").version().getVersion();
        assertEquals(2, v, "crashed upload's allocated version is not reused");
        assertEquals(Stage.REGISTERED, restarted.getVersion("m", v).getStage());
        assertThrows(NotFoundException.class, () -> restarted.getVersion("m", 1),
                "the unfinished v1 is not a readable published version");
    }

    @Test
    void checksumFailureNeverEntersLifecycle(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        UploadSession session = svc.beginUpload("m", null, LifecycleTest.sha256("expected"), null);
        Files.writeString(session.getTempFile(), "different-actual-content");
        assertThrows(ChecksumMismatchException.class,
                () -> svc.completeUpload(session.getUploadId()));
        assertTrue(svc.listVersions("m", true).isEmpty(),
                "failed verification must not produce a readable version");
        assertEquals(0, countFiles(home.resolve("blobs/tmp")),
                "failed upload storage is reclaimed immediately");
    }

    @Test
    void stateReplaysIdenticallyAfterRestart(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v1 = LifecycleTest.register(svc, "m", "c1").version().getVersion();
        long v2 = LifecycleTest.register(svc, "m", "c2").version().getVersion();
        svc.promoteToCandidate("m", v1);
        svc.promoteToProduction("m", v1);
        svc.promoteToCandidate("m", v2);
        svc.promoteToProduction("m", v2);
        svc.rollback("m", v1, "post-mortem rollback");

        ModelRegistryService restarted = TestServices.newService(home);
        assertEquals(v1, restarted.getCurrentProduction("m").getVersion());
        assertEquals(Stage.PRODUCTION, restarted.getVersion("m", v1).getStage());
        assertEquals(Stage.CANDIDATE, restarted.getVersion("m", v2).getStage());
        // audit survives restart and stays ordered
        assertEquals(svc.getAuditTrail("m").size(),
                restarted.getAuditTrail("m").size());
        assertTrue(restarted.getAuditTrail("m").stream()
                .anyMatch(e -> e.type().equals("ROLLBACK") && e.detail().contains("post-mortem")));
    }

    @Test
    void tornTrailingJournalLineIsIgnored(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        LifecycleTest.register(svc, "m", "good-content");
        Path journal = home.resolve("journal/events.jsonl");
        // Simulate a crash mid-append: append a partial, non-JSON final line.
        Files.writeString(journal,
                "{\"seq\":2,\"type\":\"REGIST", java.nio.file.StandardOpenOption.APPEND);

        ModelRegistryService restarted = TestServices.newService(home);
        assertEquals(1, restarted.listVersions("m", false).size(),
                "only complete journal records become visible");
        // New appends continue from the highest complete sequence + 1.
        long next = LifecycleTest.register(restarted, "m", "second-content")
                .version().getVersion();
        assertEquals(2, next);
        List<Long> seqs = restarted.getAuditTrail("m").stream().map(e -> e.seq()).sorted().toList();
        long prev = 0;
        for (Long s : seqs) {
            assertTrue(s > prev);
            prev = s;
        }
    }

    @Test
    void orphanBlobWithoutRegistrationIsReclaimed(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        LifecycleTest.register(svc, "m", "registered-content");
        // Simulate crash between blob commit and REGISTERED journal event by dropping a blob
        // into the content-addressed area manually.
        Path blobs = home.resolve("blobs/blobs");
        Files.createDirectories(blobs.resolve("ab"));
        Files.writeString(blobs.resolve("ab").resolve("ab" + "c".repeat(62)), "orphan");
        assertEquals(2, countFiles(home.resolve("blobs/blobs"))); // committed + orphan

        TestServices.newService(home); // recovery run
        // Registered blob survives; orphan disappears.
        assertEquals(1, countFiles(home.resolve("blobs/blobs")));
    }
}
