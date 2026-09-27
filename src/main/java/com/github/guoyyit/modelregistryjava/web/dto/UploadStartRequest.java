package com.github.guoyyit.modelregistryjava.web.dto;

public record UploadStartRequest(String model, Long version, String expectedChecksum,
                                 String checksumAlgorithm, String contentType) {
}
