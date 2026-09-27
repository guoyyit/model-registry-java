package com.github.guoyyit.modelregistryjava.registry.exception;

public class VersionAlreadyExistsException extends RegistryException {
    public VersionAlreadyExistsException(String model, String version) {
        super(RegistryErrorCode.VERSION_ALREADY_EXISTS,
                "Version already exists: " + model + "@" + version);
    }
}
