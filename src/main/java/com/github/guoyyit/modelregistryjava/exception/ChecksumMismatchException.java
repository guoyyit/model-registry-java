package com.github.guoyyit.modelregistryjava.exception;

public class ChecksumMismatchException extends ValidationException {
    public ChecksumMismatchException(String expected, String actual) {
        super("Checksum mismatch: expected=" + expected + ", actual=" + actual);
    }
}
