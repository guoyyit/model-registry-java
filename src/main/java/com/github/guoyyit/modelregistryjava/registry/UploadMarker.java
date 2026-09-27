package com.github.guoyyit.modelregistryjava.registry;

/**
 * Durable marker describing an in-progress (or interrupted) upload.
 *
 * <p>The marker is stored under {@code uploads/<id>.json} and atomically
 * replaced as the upload progresses. Content bytes live next to it.
 */
public record UploadMarker(
        String uploadId,
        String model,
        String version,
        String clientToken,
        String checksumAlg,
        String expectedChecksum,
        String contentType,
        long expectedSize,
        UploadState state,
        long createdAt,
        long updatedAt) {

    public UploadMarker withState(UploadState newState, long at) {
        return new UploadMarker(uploadId, model, version, clientToken, checksumAlg,
                expectedChecksum, contentType, expectedSize, newState, createdAt, at);
    }
}
