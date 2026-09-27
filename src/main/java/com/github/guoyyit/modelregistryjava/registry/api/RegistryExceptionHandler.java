package com.github.guoyyit.modelregistryjava.registry.api;

import com.github.guoyyit.modelregistryjava.registry.api.dto.ErrorResponse;
import com.github.guoyyit.modelregistryjava.registry.exception.DuplicateSubmissionException;
import com.github.guoyyit.modelregistryjava.registry.exception.RegistryErrorCode;
import com.github.guoyyit.modelregistryjava.registry.exception.RegistryException;
import com.github.guoyyit.modelregistryjava.registry.exception.VersionDeactivatedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class RegistryExceptionHandler {

    /** Content of a retired/offline version: 410 Gone (metadata still readable). */
    @ExceptionHandler(VersionDeactivatedException.class)
    public ResponseEntity<ErrorResponse> handleDeactivated(VersionDeactivatedException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(DuplicateSubmissionException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateSubmissionException ex) {
        // 409 Conflict: the submission was already accepted, never produces a second version
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode().name());
        body.put("message", ex.getMessage());
        ex.existingVersion().ifPresent(v -> body.put("existingVersion", v));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(RegistryException.class)
    public ResponseEntity<ErrorResponse> handle(RegistryException ex) {
        return ResponseEntity.status(status(ex.getCode()))
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage()));
    }

    private HttpStatus status(RegistryErrorCode code) {
        return switch (code) {
            case VERSION_NOT_FOUND, MODEL_NOT_FOUND, UPLOAD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VERSION_ALREADY_EXISTS, VERSION_ALREADY_TAKEN, DUPLICATE_SUBMISSION ->
                    HttpStatus.CONFLICT;
            case CHECKSUM_MISMATCH, INCOMPLETE_UPLOAD, INVALID_STAGE_TRANSITION,
                 ILLEGAL_DELETE, VERSION_DEACTIVATED, INVALID_REQUEST ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
