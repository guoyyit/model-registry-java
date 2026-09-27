package com.github.guoyyit.modelregistryjava.registry.exception;

public class ModelNotFoundException extends RegistryException {
    public ModelNotFoundException(String model) {
        super(RegistryErrorCode.MODEL_NOT_FOUND, "Model not found: " + model);
    }
}
