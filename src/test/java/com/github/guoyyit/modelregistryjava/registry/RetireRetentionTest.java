package com.github.guoyyit.modelregistryjava.registry;

import com.github.guoyyit.modelregistryjava.registry.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionDeactivatedException;
import com.github.guoyyit.modelregistryjava.registry.storage.AtomicFiles;
import com.github.guoyyit.modelregistryjava.registry.storage.JsonCodec;
import com.github.guoyyit.modelregistryjava.registry.storage.Journal;
import com.github.guoyyit.modelregistryjava.registry.storage.MessageDigestChecksum;
import com.github.guoyyit.modelregistryjava.registry.storage.PathLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetireRetentionTest {

    @TempDir
    Path tempDir;

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RegistryService service(boolean retainContent) {
        RegistryProperties props = new RegistryProperties();
        props.setStoragePath(tempDir.resolve("reg-" + retainContent).toString());
        props.setRetainRetiredContent(retainContent);
        JsonCodec json = new JsonCodec();
        AtomicFiles atomicFiles = new AtomicFiles();
        PathLayout paths = new PathLayout(props);
        Journal journal = new Journal(paths, json, atomicFiles);
        MessageDigestChecksum checksum = new MessageDigestChecksum(props.getChecksumAlgorithm());
        RegistryService service = new RegistryService(props, paths, json, atomicFiles, journal, checksum);
        service.initStorage();
        this.paths = paths;
        return service;
    }

    private PathLayout paths;

    private VersionMetadata publishAndProduce(RegistryService service, String v, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = service.beginUpload("m", v, null, sha256(data),
                "application/octet-stream", (long) data.length);
        try {
            service.appendUpload(marker.uploadId(), new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        service.completeUpload(marker.uploadId());
        service.promoteToCandidate("m", v);
        return service.promoteToProduction("m", v);
    }

    @Test
    void retireKeepsContentAndAuditByDefault() throws Exception {
        RegistryService service = service(true);
        publishAndProduce(service, "1.0.0", "weights");
        service.retire("m", "1.0.0", "offline");

        // content still readable, metadata and audit retained
        try (var in = service.readContent("m", "1.0.0")) {
            assertEquals("weights", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertNotNull(service.getVersion("m", "1.0.0"));
        assertTrue(service.auditTrail("m").stream()
                .anyMatch(e -> RegistryService.ACT_RETIRE.equals(e.action())));
    }

    @Test
    void retireReclaimsContentWhenConfiguredButKeepsMetadataAndAudit() {
        RegistryService service = service(false);
        publishAndProduce(service, "1.0.0", "weights");
        service.retire("m", "1.0.0", "offline");

        // bytes gone ...
        assertFalse(Files.exists(paths.contentFile("m", "1.0.0")));
        // ... but metadata and the audit trail are preserved
        assertEquals(VersionState.RETIRED, service.getVersion("m", "1.0.0").state());
        assertTrue(service.auditTrail("m").stream()
                .anyMatch(e -> RegistryService.ACT_RETIRE.equals(e.action())));
        assertThrows(VersionDeactivatedException.class, () -> service.readContent("m", "1.0.0"));
    }

    @Test
    void retiredMetadataStillReadableAndContentGives410Semantics() {
        RegistryService service = service(false);
        publishAndProduce(service, "1.0.0", "weights");
        service.retire("m", "1.0.0", "offline");

        assertEquals(VersionState.RETIRED, service.getVersion("m", "1.0.0").state());
        assertTrue(service.auditTrail("m").size() >= 3);
        assertThrows(VersionDeactivatedException.class, () -> service.readContent("m", "1.0.0"));
    }
}
