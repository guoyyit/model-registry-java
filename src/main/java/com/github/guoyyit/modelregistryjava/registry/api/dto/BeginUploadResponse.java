package com.github.guoyyit.modelregistryjava.registry.api.dto;

public record BeginUploadResponse(String uploadId, String model, String version, String state) {
}
