package com.github.guoyyit.modelregistryjava.web.dto;

import com.github.guoyyit.modelregistryjava.domain.AuditEvent;

import java.time.Instant;

public record AuditEventDto(long seq, String model, Long version, String type,
                            Instant at, String detail) {

    public static AuditEventDto of(AuditEvent e) {
        return new AuditEventDto(e.seq(), e.model(), e.version(), e.type(), e.at(), e.detail());
    }
}
