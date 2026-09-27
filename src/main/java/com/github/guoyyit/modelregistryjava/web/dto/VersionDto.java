package com.github.guoyyit.modelregistryjava.web.dto;

import com.github.guoyyit.modelregistryjava.domain.ModelVersion;

import java.time.Instant;

public record VersionDto(String model, long version, String stage, String checksum,
                         String checksumAlgorithm, long size, String contentType,
                         Instant registeredAt, Instant publishedAt, Instant updatedAt,
                         boolean currentProduction) {

    public static VersionDto of(ModelVersion v, boolean currentProduction) {
        return new VersionDto(v.getModel(), v.getVersion(), v.getStage().name(),
                v.getChecksum(), v.getChecksumAlgorithm(), v.getSize(), v.getContentType(),
                v.getRegisteredAt(), v.getPublishedAt(), v.getUpdatedAt(), currentProduction);
    }
}
