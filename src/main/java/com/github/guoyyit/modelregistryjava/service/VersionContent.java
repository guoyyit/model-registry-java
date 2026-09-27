package com.github.guoyyit.modelregistryjava.service;

import com.github.guoyyit.modelregistryjava.domain.ModelVersion;

import java.io.IOException;
import java.io.InputStream;

/** A version's metadata together with a fresh stream over its immutable content. */
public record VersionContent(ModelVersion version, InputStream stream) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
