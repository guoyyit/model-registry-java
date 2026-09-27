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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @TempDir
    Path tempDir;

    private RegistryProperties props;
    private PathLayout paths;
    private JsonCodec json;
    private AtomicFiles atomicFiles;
    private MessageDigestChecksum checksum;

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        props = new RegistryProperties();
        props.setStoragePath(tempDir.resolve("registry").toString());
        json = new JsonCodec();
        atomicFiles = new AtomicFiles();
        paths = new PathLayout(props);
        checksum = new MessageDigestChecksum(props.getChecksumAlgorithm());
    }

    private RegistryService newService() {
        Journal journal = new Journal(paths, json, atomicFiles);
        RegistryService service = new RegistryService(props, paths, json, atomicFiles, journal, checksum);
        service.initStorage();
        return service;
    }

    @Test
    void interruptedUploadWithWholeContentIsCompletedOnRestart() {
        RegistryService first = newService();
        byte[] content = "complete-bytes".getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = first.beginUpload("m", "1.0.0", "tok", sha256(content),
                "application/octet-stream", (long) content.length);
        try (InputStream in = new ByteArrayInputStream(content)) {
            first.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // simulate crash right after all bytes landed, before complete()
        assertTrue(Files.exists(paths.uploadMarker(marker.uploadId())));
        assertFalse(Files.exists(paths.metaFile("m", "1.0.0")));

        RegistryService second = newService();
        RecoveryReport report = second.recover();

        assertEquals(1, report.completedUploads().size());
        VersionMetadata meta = second.getVersion("m", "1.0.0");
        assertEquals(VersionState.REGISTERED, meta.state());
        assertEquals(sha256(content), meta.checksum());
        try (InputStream in = second.readContent("m", "1.0.0")) {
            assertArrayEquals(content, in.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // upload residue reclaimed after finalization
        assertFalse(Files.exists(paths.uploadMarker(marker.uploadId())));
        assertFalse(Files.exists(paths.uploadContent(marker.uploadId())));
    }

    @Test
    void partialUploadIsReclaimedAndNeverVisible() {
        RegistryService first = newService();
        byte[] full = "complete-bytes".getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = first.beginUpload("m", "1.0.0", null, sha256(full),
                "application/octet-stream", (long) full.length);
        // only half the bytes made it to disk
        byte[] partial = "complete".getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(partial)) {
            first.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        RegistryService second = newService();
        RecoveryReport report = second.recover();

        assertEquals(0, report.completedUploads().size());
        assertEquals(1, report.reclaimedUploads().size());
        assertEquals(0, second.listVersions("m").size());
        assertFalse(Files.exists(paths.uploadMarker(marker.uploadId())));
        assertFalse(Files.exists(paths.uploadContent(marker.uploadId())));
    }

    @Test
    void partialUploadIsRetainedWhenReclaimDisabled() {
        props.setReclaimIncompleteUploads(false);
        RegistryService first = newService();
        byte[] full = "complete-bytes".getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = first.beginUpload("m", "1.0.0", null, sha256(full),
                "application/octet-stream", (long) full.length);
        byte[] partial = "short".getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(partial)) {
            first.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        RecoveryReport report = newService().recover();
        assertEquals(0, report.completedUploads().size());
        assertEquals(1, report.suspiciousUploads().size());
        // storage is intentionally preserved for manual inspection
        assertTrue(Files.exists(paths.uploadMarker(marker.uploadId())));
        // still not visible as a version
        assertEquals(0, newService().listVersions("m").size());
    }

    @Test
    void tornPromotionIsReconciledFromJournalToSingleProduction() throws Exception {
        RegistryService first = newService();
        byte[] c1 = "v1".getBytes(StandardCharsets.UTF_8);
        byte[] c2 = "v2-longer".getBytes(StandardCharsets.UTF_8);
        publish(first, "1.0.0", c1);
        publish(first, "2.0.0", c2);
        first.promoteToCandidate("m", "1.0.0");
        first.promoteToProduction("m", "1.0.0");
        first.promoteToCandidate("m", "2.0.0");

        // Journal the promotion decision, then simulate a torn state:
        // new version promoted, old version's state update lost.
        Journal journal = new Journal(paths, json, atomicFiles);
        journal.append(System.currentTimeMillis(), RegistryService.ACT_PROMOTE_PRODUCTION,
                "m", "2.0.0", "simulated");
        // deliberately make BOTH claim PRODUCTION on disk
        VersionMetadata v1 = json.read(Files.readAllBytes(paths.metaFile("m", "1.0.0")),
                VersionMetadata.class)
                .withState(VersionState.PRODUCTION, System.currentTimeMillis());
        atomicFiles.writeAtomically(paths.metaFile("m", "1.0.0"), json.writeBytes(v1));
        VersionMetadata v2 = json.read(Files.readAllBytes(paths.metaFile("m", "2.0.0")),
                VersionMetadata.class)
                .withState(VersionState.PRODUCTION, System.currentTimeMillis());
        atomicFiles.writeAtomically(paths.metaFile("m", "2.0.0"), json.writeBytes(v2));

        RegistryService second = newService();
        second.recover();

        assertEquals("2.0.0", second.getProduction("m").version());
        assertEquals(VersionState.RETIRED, second.getVersion("m", "1.0.0").state());
    }

    private void publish(RegistryService service, String version, byte[] data) {
        UploadMarker marker = service.beginUpload("m", version, null, sha256(data),
                "application/octet-stream", (long) data.length);
        try (InputStream in = new ByteArrayInputStream(data)) {
            service.appendUpload(marker.uploadId(), in);
            service.completeUpload(marker.uploadId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
