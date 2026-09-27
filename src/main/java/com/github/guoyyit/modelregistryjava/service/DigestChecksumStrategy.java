package com.github.guoyyit.modelregistryjava.service;

import com.github.guoyyit.modelregistryjava.exception.ChecksumMismatchException;
import com.github.guoyyit.modelregistryjava.exception.ValidationException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@link ChecksumStrategy} backed by a JDK {@link MessageDigest}.
 *
 * <p>Configurable algorithm name mapping (the public name uses the common "sha-256" spelling):
 * <ul>
 *   <li>{@code sha-256} -> SHA-256 (default, recommended)</li>
 *   <li>{@code sha-1}   -> SHA-1 (legacy interop only)</li>
 *   <li>{@code md5}     -> MD5 (legacy interop only)</li>
 * </ul>
 *
 * <p>Digests are compared as lowercase hex strings. A mismatch fails the upload; the version
 * never enters the lifecycle and the staged storage is reclaimed.
 */
public class DigestChecksumStrategy implements ChecksumStrategy {

    private final String algorithm;
    private final MessageDigest digestPrototype;

    public DigestChecksumStrategy(String algorithm) {
        this.algorithm = normalizeName(algorithm);
        this.digestPrototype = createDigest(this.algorithm);
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            throw new ValidationException("Checksum algorithm must be configured");
        }
        String a = raw.trim().toLowerCase();
        return switch (a) {
            case "sha-256", "sha256" -> "sha-256";
            case "sha-1", "sha1" -> "sha-1";
            case "md5" -> "md5";
            default -> throw new ValidationException("Unsupported checksum algorithm: " + raw);
        };
    }

    private static MessageDigest createDigest(String normalized) {
        String jdkName = switch (normalized) {
            case "sha-256" -> "SHA-256";
            case "sha-1" -> "SHA-1";
            case "md5" -> "MD5";
            default -> throw new ValidationException("Unsupported checksum algorithm: " + normalized);
        };
        try {
            return MessageDigest.getInstance(jdkName);
        } catch (NoSuchAlgorithmException e) {
            throw new ValidationException("Unsupported checksum algorithm: " + normalized, e);
        }
    }

    private MessageDigest newDigest() {
        try {
            return (MessageDigest) digestPrototype.clone();
        } catch (CloneNotSupportedException e) {
            return createDigest(algorithm);
        }
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public String computeHex(InputStream in) throws IOException {
        MessageDigest md = newDigest();
        try (InputStream source = new BufferedInputStream(in)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = source.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    @Override
    public void verify(InputStream in, String expectedHex) throws IOException {
        if (expectedHex == null || expectedHex.isBlank()) {
            throw new ValidationException("Expected checksum must be provided");
        }
        String actual = computeHex(in);
        String expected = expectedHex.trim().toLowerCase();
        if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ChecksumMismatchException(expected, actual);
        }
    }
}
