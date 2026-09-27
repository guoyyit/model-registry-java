package com.github.guoyyit.modelregistryjava.registry.exception;

/**
 * Stable error codes returned to API clients.
 */
public enum RegistryErrorCode {
    VERSION_NOT_FOUND,
    MODEL_NOT_FOUND,
    VERSION_ALREADY_EXISTS,
    VERSION_ALREADY_TAKEN,
    DUPLICATE_SUBMISSION,
    CHECKSUM_MISMATCH,
    INCOMPLETE_UPLOAD,
    INVALID_STAGE_TRANSITION,
    ILLEGAL_DELETE,
    VERSION_DEACTIVATED,
    UPLOAD_NOT_FOUND,
    INVALID_REQUEST
}
