package com.github.guoyyit.modelregistryjava.registry.exception;

public class VersionDeactivatedException extends RegistryException {
    public VersionDeactivatedException(String model, String version) {
        super(RegistryErrorCode.VERSION_DEACTIVATED,
                "Version is retired/offline: " + model + "@" + version);
    }
}
