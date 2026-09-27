package com.github.guoyyit.modelregistryjava.registry.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around Jackson used for all durable JSON documents.
 */
@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this.mapper = new ObjectMapper();
        // stable on-disk format; records are immutable and serialize component-by-component
        this.mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize " + value.getClass(), e);
        }
    }

    public byte[] writeBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize " + type, e);
        }
    }

    public <T> T read(byte[] json, Class<T> type) {
        return read(new String(json, StandardCharsets.UTF_8), type);
    }
}
