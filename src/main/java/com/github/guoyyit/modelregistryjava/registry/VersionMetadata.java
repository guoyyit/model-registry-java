package com.github.guoyyit.modelregistryjava.registry;

/**
 * Immutable metadata describing one published model version.
 *
 * <p>After publication none of the fields ever change. The only mutable thing
 * about a version is its lifecycle {@link VersionState}, which is persisted in
 * the same metadata file via atomic replace; all other fields are effectively
 * frozen.
 */
public record VersionMetadata(
        String model,
        String version,
        String checksumAlg,
        String checksum,
        long size,
        String contentType,
        String clientToken,
        long createdAt,
        VersionState state,
        long stateUpdatedAt) {

    public VersionMetadata withState(VersionState newState, long at) {
        return new VersionMetadata(model, version, checksumAlg, checksum, size,
                contentType, clientToken, createdAt, newState, at);
    }
}
