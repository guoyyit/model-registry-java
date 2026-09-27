package com.github.guoyyit.modelregistryjava.registry.storage;

import com.github.guoyyit.modelregistryjava.registry.exception.InvalidRequestException;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@link ChecksumStrategy} backed by {@link MessageDigest}.
 *
 * <p>The algorithm is configurable via {@code registry.checksum-algorithm};
 * the default is {@code SHA-256}.
 */
public class MessageDigestChecksum implements ChecksumStrategy {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final String algorithm;

    public MessageDigestChecksum(String algorithm) {
        // fail fast on an unsupported/typo'd algorithm at startup
        try {
            MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new InvalidRequestException("Unsupported checksum algorithm: " + algorithm);
        }
        this.algorithm = algorithm;
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public String computeHex(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Checksum algorithm disappeared: " + algorithm, e);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
