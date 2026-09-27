package com.github.guoyyit.modelregistryjava.registry;

import com.github.guoyyit.modelregistryjava.registry.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.registry.storage.AtomicFiles;
import com.github.guoyyit.modelregistryjava.registry.storage.JsonCodec;
import com.github.guoyyit.modelregistryjava.registry.storage.Journal;
import com.github.guoyyit.modelregistryjava.registry.storage.MessageDigestChecksum;
import com.github.guoyyit.modelregistryjava.registry.storage.PathLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChecksumConfigTest {

    @TempDir
    Path tempDir;

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RegistryService serviceWithAlgorithm(String algorithm) {
        RegistryProperties props = new RegistryProperties();
        props.setStoragePath(tempDir.resolve("reg-" + algorithm).toString());
        props.setChecksumAlgorithm(algorithm);
        JsonCodec json = new JsonCodec();
        AtomicFiles atomicFiles = new AtomicFiles();
        PathLayout paths = new PathLayout(props);
        Journal journal = new Journal(paths, json, atomicFiles);
        MessageDigestChecksum checksum = new MessageDigestChecksum(algorithm);
        RegistryService service = new RegistryService(props, paths, json, atomicFiles, journal, checksum);
        service.initStorage();
        return service;
    }

    @Test
    void checksumAlgorithmIsConfigurable() throws Exception {
        RegistryService service = serviceWithAlgorithm("MD5");
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = service.beginUpload("m", "1.0.0", null,
                digest("MD5", data), "application/octet-stream", (long) data.length);
        service.appendUpload(marker.uploadId(), new ByteArrayInputStream(data));
        VersionMetadata meta = service.completeUpload(marker.uploadId());
        assertEquals("MD5", meta.checksumAlg());
        assertEquals(digest("MD5", data), meta.checksum());
    }

    @Test
    void invalidAlgorithmFailsFast() {
        assertThrows(RuntimeException.class,
                () -> new MessageDigestChecksum("NOT-A-REAL-ALGORITHM"));
    }

    @Test
    void sha256IsDefault() throws Exception {
        RegistryService service = serviceWithAlgorithm("SHA-256");
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        UploadMarker marker = service.beginUpload("m", "1.0.0", null,
                digest("SHA-256", data), "application/octet-stream", (long) data.length);
        service.appendUpload(marker.uploadId(), new ByteArrayInputStream(data));
        assertEquals("SHA-256", service.completeUpload(marker.uploadId()).checksumAlg());
    }
}
