package com.github.guoyyit.modelregistryjava.registry;

/**
 * One append-only audit / journal entry.
 *
 * <p>The journal is the permanent audit trail. It is never rewritten or
 * truncated; even fully deleted versions leave at least one event behind.
 *
 * @param seq    monotonically increasing, gap-free sequence number
 * @param at     event timestamp (epoch millis)
 * @param action event type (see {@code AuditAction})
 * @param model  model name
 * @param version version id
 * @param detail free-form, event-specific detail (may be empty)
 */
public record AuditEvent(
        long seq,
        long at,
        String action,
        String model,
        String version,
        String detail) {
}
