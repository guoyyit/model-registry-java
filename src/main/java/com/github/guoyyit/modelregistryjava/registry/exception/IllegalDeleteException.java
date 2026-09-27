package com.github.guoyyit.modelregistryjava.registry.exception;

public class IllegalDeleteException extends RegistryException {
    public IllegalDeleteException(String message) {
        super(RegistryErrorCode.ILLEGAL_DELETE, message);
    }
}
