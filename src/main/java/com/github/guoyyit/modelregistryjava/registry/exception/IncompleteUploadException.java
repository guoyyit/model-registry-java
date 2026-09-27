package com.github.guoyyit.modelregistryjava.registry.exception;

public class IncompleteUploadException extends RegistryException {
    public IncompleteUploadException(String uploadId) {
        super(RegistryErrorCode.INCOMPLETE_UPLOAD,
                "Upload is not complete: " + uploadId);
    }
}
