package com.github.guoyyit.modelregistryjava.web;

import com.github.guoyyit.modelregistryjava.exception.ChecksumMismatchException;
import com.github.guoyyit.modelregistryjava.exception.ConflictException;
import com.github.guoyyit.modelregistryjava.exception.GoneException;
import com.github.guoyyit.modelregistryjava.exception.NotFoundException;
import com.github.guoyyit.modelregistryjava.exception.RegistryException;
import com.github.guoyyit.modelregistryjava.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", error));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(GoneException.class)
    public ResponseEntity<Map<String, Object>> gone(GoneException e) {
        return body(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<Map<String, Object>> checksum(ChecksumMismatchException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(ValidationException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<Map<String, Object>> registry(RegistryException e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
}
