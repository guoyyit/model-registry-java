package com.github.guoyyit.modelregistryjava.registry.exception;

public class InvalidRequestException extends RegistryException {
    public InvalidRequestException(String message) {
        super(RegistryErrorCode.INVALID_REQUEST, message);
    }
}
