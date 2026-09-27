package com.github.guoyyit.modelregistryjava.registry;

import com.github.guoyyit.modelregistryjava.registry.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.registry.exception.ChecksumMismatchException;
import com.github.guoyyit.modelregistryjava.registry.exception.DuplicateSubmissionException;
import com.github.guoyyit.modelregistryjava.registry.exception.InvalidRequestException;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionAlreadyExistsException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryServiceTest {

    @TempDir
    Path tempDir;

    private RegistryService registry;

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

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private VersionMetadata publish(String model, String version, byte[] data, String token) {
        String sum = sha256(data);
        UploadMarker marker = registry.beginUpload(model, version, token, sum,
                "application/octet-stream", (long) data.length);
        try (InputStream in = new ByteArrayInputStream(data)) {
            registry.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry.completeUpload(marker.uploadId());
    }

    private VersionMetadata publish(String model, String version, String content, String token) {
        return publish(model, version, content.getBytes(StandardCharsets.UTF_8), token);
    }

    private byte[] readAll(String model, String version) throws Exception {
        try (InputStream in = registry.readContent(model, version)) {
            return in.readAllBytes();
        }
    }

    @Test
    void publishesImmutableVersionAndReadsItBack() throws Exception {
        byte[] content = "model-weights-v1".getBytes(StandardCharsets.UTF_8);
        VersionMetadata meta = publish("m1", "1.0.0", content, null);

        assertEquals(VersionState.REGISTERED, meta.state());
        assertEquals(sha256(content), meta.checksum());
        assertEquals(content.length, meta.size());
        assertArrayEquals(content, readAll("m1", "1.0.0"));

        // metadata as stored equals what reads return
        assertEquals(meta, registry.getVersion("m1", "1.0.0"));
    }

    @Test
    void rejectsWrongChecksumAndNeverPublishes() {
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = registry.beginUpload("m1", "1.0.0", null,
                "00".repeat(32), "application/octet-stream", (long) content.length);
        try (InputStream in = new ByteArrayInputStream(content)) {
            registry.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThrows(ChecksumMismatchException.class,
                () -> registry.completeUpload(marker.uploadId()));

        // no partial version visible, version id is free to reuse
        assertEquals(0, registry.listVersions("m1").size());
    }

    @Test
    void rejectsIncompleteContentBySize() {
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = registry.beginUpload("m1", "1.0.0", null,
                sha256(content), "application/octet-stream", 99L);
        try (InputStream in = new ByteArrayInputStream(content)) {
            registry.appendUpload(marker.uploadId(), in);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThrows(InvalidRequestException.class,
                () -> registry.completeUpload(marker.uploadId()));
        assertEquals(0, registry.listVersions("m1").size());
    }

    @Test
    void duplicateClientTokenDoesNotCreateSecondVersion() {
        VersionMetadata first = publish("m1", "1.0.0", "v1", "token-x");
        DuplicateSubmissionException ex = assertThrows(DuplicateSubmissionException.class,
                () -> registry.beginUpload("m1", "1.0.0", "token-x",
                        sha256("other".getBytes()), null, 5L));
        assertEquals("1.0.0", ex.existingVersion().orElseThrow());

        // only one effective version exists
        assertEquals(1, registry.listVersions("m1").size());
        assertEquals(first, registry.listVersions("m1").get(0));
    }

    @Test
    void sameVersionIdCannotBeOverwritten() {
        publish("m1", "1.0.0", "v1", "token-1");
        assertThrows(VersionAlreadyExistsException.class,
                () -> publish("m1", "1.0.0", "different-content", "token-2"));
    }

    @Test
    void binaryRoundTripIsByteExactForHighBytes() throws Exception {
        byte[] content = new byte[256];
        for (int i = 0; i < 256; i++) {
            content[i] = (byte) i; // every byte value incl. 0x00 and high bytes
        }
        publish("bin", "1.0.0", content, null);
        assertArrayEquals(content, readAll("bin", "1.0.0"));
    }

    @Test
    void auditTrailIsAppended() {
        publish("m1", "1.0.0", "v1", null);
        var events = registry.auditTrail("m1");
        assertTrue(events.stream().anyMatch(e -> e.action().equals(RegistryService.ACT_PUBLISH)));
        assertEquals(1, events.get(0).seq());
        assertNotNull(events.get(0).at());
    }
}
