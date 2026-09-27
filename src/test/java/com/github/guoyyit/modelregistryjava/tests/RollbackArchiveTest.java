package com.github.guoyyit.modelregistryjava.tests;

import com.github.guoyyit.modelregistryjava.domain.AuditEvent;
import com.github.guoyyit.modelregistryjava.domain.Stage;
import com.github.guoyyit.modelregistryjava.exception.ConflictException;
import com.github.guoyyit.modelregistryjava.exception.GoneException;
import com.github.guoyyit.modelregistryjava.service.ModelRegistryService;
import com.github.guoyyit.modelregistryjava.service.VersionContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackArchiveTest {

    private String readAll(VersionContent c) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        c.stream().transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rollsBackToAnyHistoricalVersionDeterministically(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v1 = LifecycleTest.register(svc, "m", "content-1").version().getVersion();
        long v2 = LifecycleTest.register(svc, "m", "content-2").version().getVersion();
        long v3 = LifecycleTest.register(svc, "m", "content-3").version().getVersion();
        svc.promoteToCandidate("m", v1);
        svc.promoteToProduction("m", v1);
        svc.promoteToCandidate("m", v2);
        svc.promoteToProduction("m", v2);
        svc.promoteToCandidate("m", v3);
        svc.promoteToProduction("m", v3);

        // Roll back past v2 straight to the oldest version
        assertEquals(v1, svc.rollback("m", v1, "incident").getVersion());
        try (VersionContent c = svc.readProductionContent("m")) {
            assertEquals("content-1", readAll(c));
        }
        assertEquals(Stage.CANDIDATE, svc.getVersion("m", v3).getStage());
        assertEquals(Stage.CANDIDATE, svc.getVersion("m", v2).getStage());

        // Rolling back again to the same target is a stable no-op
        assertEquals(v1, svc.rollback("m", v1, "again").getVersion());

        // And forward to another historical version works as well
        assertEquals(v3, svc.rollback("m", v3, "forward-again").getVersion());
        try (VersionContent c = svc.readProductionContent("m")) {
            assertEquals("content-3", readAll(c));
        }
    }

    @Test
    void productionCannotBeArchivedDirectly(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v1 = LifecycleTest.register(svc, "m", "content-1").version().getVersion();
        svc.promoteToCandidate("m", v1);
        svc.promoteToProduction("m", v1);
        assertThrows(ConflictException.class, () -> svc.archive("m", v1, "nope"));
        assertEquals(Stage.PRODUCTION, svc.getVersion("m", v1).getStage(),
                "production version must not be deleted/offlined directly");
    }

    @Test
    void archivedVersionKeepsAuditButContentIsOffline(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v1 = LifecycleTest.register(svc, "m", "content-1").version().getVersion();
        long v2 = LifecycleTest.register(svc, "m", "content-2").version().getVersion();
        svc.archive("m", v1, "not needed");

        // Metadata remains for audit
        assertEquals(Stage.ARCHIVED, svc.getVersion("m", v1).getStage());
        // Content reads are rejected
        assertThrows(GoneException.class, () -> svc.readContent("m", v1));
        // Default listing hides archived; includeArchived shows it
        assertEquals(1, svc.listVersions("m", false).size());
        assertEquals(2, svc.listVersions("m", true).size());
        // Audit history preserved
        assertFalse(svc.getAuditTrail("m").isEmpty());
        assertTrue(svc.getAuditTrail("m").stream().map(AuditEvent::type).anyMatch("ARCHIVED"::equals));
        // Reads outside the archive scope are unaffected
        try (VersionContent c = svc.readContent("m", v2)) {
            assertEquals("content-2", readAll(c));
        }
        // Restore brings it back into the lifecycle at REGISTERED
        assertEquals(Stage.REGISTERED, svc.restore("m", v1).getStage());
        assertDoesNotThrow(() -> {
            try (VersionContent c = svc.readContent("m", v1)) {
                assertEquals("content-1", readAll(c));
            }
        });
    }

    @Test
    void cannotRollBackToArchivedVersion(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v1 = LifecycleTest.register(svc, "m", "content-1").version().getVersion();
        long v2 = LifecycleTest.register(svc, "m", "content-2").version().getVersion();
        svc.promoteToCandidate("m", v1);
        svc.promoteToProduction("m", v1);
        // v2 never went live; archive it, then attempt rollback
        svc.archive("m", v2, "cold");
        assertThrows(ConflictException.class, () -> svc.rollback("m", v2, "blocked"));
    }
}
