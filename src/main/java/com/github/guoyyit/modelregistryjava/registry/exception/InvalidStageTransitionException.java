package com.github.guoyyit.modelregistryjava.registry.exception;

public class InvalidStageTransitionException extends RegistryException {
    public InvalidStageTransitionException(String message) {
        super(RegistryErrorCode.INVALID_STAGE_TRANSITION, message);
    }
}
