package com.github.guoyyit.modelregistryjava.registry.exception;

public class VersionTakenException extends RegistryException {
    public VersionTakenException(String model, String version) {
        super(RegistryErrorCode.VERSION_ALREADY_TAKEN,
                "Version id already taken (published, in progress or tombstoned): "
                        + model + "@" + version);
    }
}
