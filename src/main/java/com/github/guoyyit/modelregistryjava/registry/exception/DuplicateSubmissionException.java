package com.github.guoyyit.modelregistryjava.registry.exception;

import java.util.Optional;

/**
 * Thrown when an upload is repeated.
 */
public class DuplicateSubmissionException extends RegistryException {

    private final String existingVersion;

    public DuplicateSubmissionException(String clientToken, Optional<String> existingVersion) {
        super(RegistryErrorCode.DUPLICATE_SUBMISSION,
                "Duplicate submission for token " + clientToken);
        this.existingVersion = existingVersion.orElse(null);
    }

    /** Version already produced by the repeated submission, if known. */
    public Optional<String> existingVersion() {
        return Optional.ofNullable(existingVersion);
    }
}
