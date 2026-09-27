package com.github.guoyyit.modelregistryjava.exception;

public class ValidationException extends RegistryException {
    public ValidationException(String message) { super(message); }
    public ValidationException(String message, Throwable cause) { super(message, cause); }
}
