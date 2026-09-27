package com.github.guoyyit.modelregistryjava.domain;

import java.time.Instant;

/**
 * One append-only audit record. Once written it is never modified or deleted.
 */
public record AuditEvent(long seq, String model, Long version, String type, Instant at, String detail) {
}
