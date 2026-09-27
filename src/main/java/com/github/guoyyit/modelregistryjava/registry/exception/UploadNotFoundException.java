package com.github.guoyyit.modelregistryjava.registry.exception;

public class UploadNotFoundException extends RegistryException {
    public UploadNotFoundException(String uploadId) {
        super(RegistryErrorCode.UPLOAD_NOT_FOUND, "Upload not found: " + uploadId);
    }
}
