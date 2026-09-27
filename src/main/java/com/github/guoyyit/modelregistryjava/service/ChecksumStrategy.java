package com.github.guoyyit.modelregistryjava.service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pluggable content verification strategy.
 * Implementations compute the digest of a content stream and compare it with an expected value.
 */
public interface ChecksumStrategy {

    /** Lowercase algorithm name, e.g. "sha-256". */
    String algorithm();

    /** Compute the lowercase hex digest of the given stream. */
    String computeHex(InputStream in) throws IOException;

    /**
     * Verify content. Throws
     * {@link com.github.guoyyit.modelregistryjava.exception.ChecksumMismatchException} on mismatch.
     */
    void verify(InputStream in, String expectedHex) throws IOException;
}
