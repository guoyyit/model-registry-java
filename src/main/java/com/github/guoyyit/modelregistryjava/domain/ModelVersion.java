package com.github.guoyyit.modelregistryjava.domain;

import java.time.Instant;

/**
 * Immutable snapshot of a model version's current metadata, rebuilt from the event journal.
 */
public final class ModelVersion {

    private final String model;
    private final long version;
    private final Stage stage;
    private final String blobKey;
    private final String checksum;
    private final String checksumAlgorithm;
    private final long size;
    private final String contentType;
    private final Instant registeredAt;
    private final Instant publishedAt;
    private final Instant updatedAt;

    public ModelVersion(String model, long version, Stage stage, String blobKey, String checksum,
                        String checksumAlgorithm, long size, String contentType,
                        Instant registeredAt, Instant publishedAt, Instant updatedAt) {
        this.model = model;
        this.version = version;
        this.stage = stage;
        this.blobKey = blobKey;
        this.checksum = checksum;
        this.checksumAlgorithm = checksumAlgorithm;
        this.size = size;
        this.contentType = contentType;
        this.registeredAt = registeredAt;
        this.publishedAt = publishedAt;
        this.updatedAt = updatedAt;
    }

    public ModelVersion withStage(Stage newStage, Instant at) {
        return new ModelVersion(model, version, newStage, blobKey, checksum, checksumAlgorithm,
                size, contentType, registeredAt, publishedAt, at);
    }

    /** Defensive copy so readers can never mutate registry state through a returned instance. */
    public ModelVersion snapshot() {
        return new ModelVersion(model, version, stage, blobKey, checksum, checksumAlgorithm,
                size, contentType, registeredAt, publishedAt, updatedAt);
    }

    public String getModel() { return model; }
    public long getVersion() { return version; }
    public Stage getStage() { return stage; }
    public String getBlobKey() { return blobKey; }
    public String getChecksum() { return checksum; }
    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public long getSize() { return size; }
    public String getContentType() { return contentType; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
