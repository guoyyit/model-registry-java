package com.github.guoyyit.modelregistryjava.tests;

import com.github.guoyyit.modelregistryjava.exception.ChecksumMismatchException;
import com.github.guoyyit.modelregistryjava.exception.ValidationException;
import com.github.guoyyit.modelregistryjava.service.DigestChecksumStrategy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChecksumStrategyTest {

    private static String hex(String algorithm, byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
    }

    @Test
    void verifiesMatchingSha256() throws Exception {
        byte[] data = "model-weights-v1".getBytes(StandardCharsets.UTF_8);
        String expected = hex("SHA-256", data);
        DigestChecksumStrategy strategy = new DigestChecksumStrategy("sha-256");
        assertEquals("sha-256", strategy.algorithm());
        assertDoesNotThrow(() ->
                strategy.verify(new ByteArrayInputStream(data), expected));
    }

    @Test
    void rejectsMismatchedChecksum() {
        byte[] data = "model-weights-v1".getBytes(StandardCharsets.UTF_8);
        DigestChecksumStrategy strategy = new DigestChecksumStrategy("sha-256");
        assertThrows(ChecksumMismatchException.class,
                () -> strategy.verify(new ByteArrayInputStream(data),
                        "0".repeat(64)));
    }

    @Test
    void rejectsMissingExpectedChecksum() {
        DigestChecksumStrategy strategy = new DigestChecksumStrategy("sha-256");
        assertThrows(ValidationException.class,
                () -> strategy.verify(new ByteArrayInputStream(new byte[]{1}), "  "));
    }

    @Test
    void supportsConfigurableAlgorithms() throws Exception {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(hex("MD5", data),
                new DigestChecksumStrategy("md5").computeHex(new ByteArrayInputStream(data)));
        assertEquals(hex("SHA-1", data),
                new DigestChecksumStrategy("sha1").computeHex(new ByteArrayInputStream(data)));
        assertThrows(ValidationException.class,
                () -> new DigestChecksumStrategy("nope-512"));
    }
}
