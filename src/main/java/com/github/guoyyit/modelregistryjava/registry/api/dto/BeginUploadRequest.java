package com.github.guoyyit.modelregistryjava.registry.api.dto;

public record BeginUploadRequest(
        String model,
        String version,
        String clientToken,
        String expectedChecksum,
        String contentType,
        Long expectedSize) {
}
