package com.github.guoyyit.modelregistryjava.registry.exception;

public class VersionNotFoundException extends RegistryException {
    public VersionNotFoundException(String model, String version) {
        super(RegistryErrorCode.VERSION_NOT_FOUND,
                "Version not found: " + model + "@" + version);
    }
}
