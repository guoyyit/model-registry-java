package com.github.guoyyit.modelregistryjava.registry.exception;

public class ChecksumMismatchException extends RegistryException {
    public ChecksumMismatchException(String expected, String actual) {
        super(RegistryErrorCode.CHECKSUM_MISMATCH,
                "Checksum mismatch. expected=" + expected + ", actual=" + actual);
    }
}
