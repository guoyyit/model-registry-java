package com.github.guoyyit.modelregistryjava.registry;

import com.github.guoyyit.modelregistryjava.registry.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.registry.exception.IllegalDeleteException;
import com.github.guoyyit.modelregistryjava.registry.exception.InvalidStageTransitionException;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionNotFoundException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleTest {

    @TempDir
    Path tempDir;

    private RegistryService registry;
    private Journal journal;

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
        journal = new Journal(paths, json, atomicFiles);
        MessageDigestChecksum checksum = new MessageDigestChecksum(props.getChecksumAlgorithm());
        registry = new RegistryService(props, paths, json, atomicFiles, journal, checksum);
        registry.initStorage();
    }

    private VersionMetadata publish(String version, String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = registry.beginUpload("m", version, null, sha256(data),
                "application/octet-stream", (long) data.length);
        try (InputStream in = new ByteArrayInputStream(data)) {
            registry.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry.completeUpload(marker.uploadId());
    }

    @Test
    void stagesAdvanceInOrderAndSkippingIsRejected() {
        publish("1.0.0", "v1");

        // REGISTERED -> PRODUCTION must be rejected (skip of CANDIDATE)
        assertThrows(InvalidStageTransitionException.class,
                () -> registry.promoteToProduction("m", "1.0.0"));

        VersionMetadata candidate = registry.promoteToCandidate("m", "1.0.0");
        assertEquals(VersionState.CANDIDATE, candidate.state());

        // CANDIDATE -> CANDIDATE again is rejected
        assertThrows(InvalidStageTransitionException.class,
                () -> registry.promoteToCandidate("m", "1.0.0"));

        VersionMetadata production = registry.promoteToProduction("m", "1.0.0");
        assertEquals(VersionState.PRODUCTION, production.state());
        assertEquals("1.0.0", registry.getProduction("m").version());
    }

    @Test
    void promotingNewProductionRetiresPrevious() {
        publish("1.0.0", "v1");
        publish("1.1.0", "v2");
        registry.promoteToCandidate("m", "1.0.0");
        registry.promoteToProduction("m", "1.0.0");
        registry.promoteToCandidate("m", "1.1.0");
        registry.promoteToProduction("m", "1.1.0");

        assertEquals(VersionState.RETIRED, registry.getVersion("m", "1.0.0").state());
        assertEquals(VersionState.PRODUCTION, registry.getVersion("m", "1.1.0").state());
    }

    @Test
    void rollbackToAnyHistoricalVersionIsDeterministic() {
        publish("1.0.0", "v1");
        publish("2.0.0", "v2");
        registry.promoteToCandidate("m", "1.0.0");
        registry.promoteToProduction("m", "1.0.0");
        registry.promoteToCandidate("m", "2.0.0");
        registry.promoteToProduction("m", "2.0.0");

        // roll back across history to 1.0.0
        VersionMetadata rolled = registry.rollback("m", "1.0.0", "incident-42");
        assertEquals(VersionState.PRODUCTION, rolled.state());
        assertEquals("1.0.0", registry.getProduction("m").version());
        assertEquals(VersionState.RETIRED, registry.getVersion("m", "2.0.0").state());

        // rollback is traceable in the audit trail
        AuditEvent rollbackEvent = journal.readAll().stream()
                .filter(e -> RegistryService.ACT_ROLLBACK.equals(e.action()))
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("1.0.0", rollbackEvent.version());
        assertTrue(rollbackEvent.detail().contains("incident-42"));
        assertTrue(rollbackEvent.detail().contains("from=2.0.0"));
    }

    @Test
    void rollbackCanRestoreARetiredVersion() {
        publish("1.0.0", "v1");
        publish("2.0.0", "v2");
        registry.promoteToCandidate("m", "1.0.0");
        registry.promoteToProduction("m", "1.0.0");
        registry.promoteToCandidate("m", "2.0.0");
        registry.promoteToProduction("m", "2.0.0");

        // explicit rollback is the only allowed "backward" move for a RETIRED version
        VersionMetadata back = registry.rollback("m", "1.0.0", "restore");
        assertEquals(VersionState.PRODUCTION, back.state());
    }

    @Test
    void productionCannotBeDeletedDirectlyButCanBeRetired() {
        publish("1.0.0", "v1");
        registry.promoteToCandidate("m", "1.0.0");
        registry.promoteToProduction("m", "1.0.0");

        assertThrows(IllegalDeleteException.class,
                () -> registry.deleteVersion("m", "1.0.0"));

        VersionMetadata retired = registry.retire("m", "1.0.0", "offline for compliance");
        assertEquals(VersionState.RETIRED, retired.state());
        // content and metadata remain readable by default after retire
        assertNotNull(registry.getVersion("m", "1.0.0"));
        // and there is no production version anymore
        assertThrows(VersionNotFoundException.class, () -> registry.getProduction("m"));
    }

    @Test
    void retiredVersionAuditSurvivesAndOtherReadsUnaffected() {
        publish("1.0.0", "v1");
        publish("2.0.0", "v2");
        registry.promoteToCandidate("m", "1.0.0");
        registry.promoteToProduction("m", "1.0.0");
        registry.retire("m", "1.0.0", "old");

        // audit history of retired version is still fully present
        long eventsForRetired = registry.auditTrail("m").stream()
                .filter(e -> "1.0.0".equals(e.version())).count();
        assertTrue(eventsForRetired >= 2, "expected publish+retire events, got " + eventsForRetired);

        // a different, non-retired version is completely unaffected
        VersionMetadata v2 = registry.getVersion("m", "2.0.0");
        assertEquals(VersionState.REGISTERED, v2.state());
    }

    @Test
    void neverProducedVersionCanBeDeleted() throws Exception {
        publish("0.9.0", "beta");
        registry.deleteVersion("m", "0.9.0");
        assertThrows(VersionNotFoundException.class, () -> registry.getVersion("m", "0.9.0"));
        // id stays occupied (tombstoned): cannot be re-registered
        assertThrows(Exception.class, () -> publish("0.9.0", "beta-again"));
    }
}
