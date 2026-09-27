package com.github.guoyyit.modelregistryjava.service;

import java.nio.file.Path;

/** A live staged upload handle. */
public final class UploadSession {

    private final String uploadId;
    private final String model;
    private final long version;
    private final Path tempFile;
    private final String expectedChecksum;
    private final String contentType;

    public UploadSession(String uploadId, String model, long version, Path tempFile,
                         String expectedChecksum, String contentType) {
        this.uploadId = uploadId;
        this.model = model;
        this.version = version;
        this.tempFile = tempFile;
        this.expectedChecksum = expectedChecksum;
        this.contentType = contentType;
    }

    public String getUploadId() { return uploadId; }
    public String getModel() { return model; }
    public long getVersion() { return version; }
    public Path getTempFile() { return tempFile; }
    public String getExpectedChecksum() { return expectedChecksum; }
    public String getContentType() { return contentType; }
}
