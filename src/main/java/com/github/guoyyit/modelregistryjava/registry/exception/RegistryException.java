package com.github.guoyyit.modelregistryjava.registry.exception;

/**
 * Base class for all registry domain errors.
 */
public class RegistryException extends RuntimeException {

    private final RegistryErrorCode code;

    public RegistryException(RegistryErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RegistryErrorCode getCode() {
        return code;
    }
}
