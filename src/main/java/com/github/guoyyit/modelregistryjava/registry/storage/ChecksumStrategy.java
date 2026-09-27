package com.github.guoyyit.modelregistryjava.registry.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pluggable content verification strategy.
 *
 * <p>The strategy used by the registry is configurable via
 * {@code registry.checksum-algorithm} (default {@code SHA-256}). Any uploaded
 * version must present the checksum produced by this strategy; a version whose
 * computed checksum differs, or whose byte stream is shorter/longer than the
 * declared length, is rejected and never enters the lifecycle.
 */
public interface ChecksumStrategy {

    /** Algorithm name advertised to clients (e.g. SHA-256). */
    String algorithm();

    /**
     * Compute the lowercase hex checksum of the full stream.
     */
    String computeHex(InputStream in) throws IOException;
}
