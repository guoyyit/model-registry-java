package com.github.guoyyit.modelregistryjava.domain;

/**
 * Lifecycle stage of a model version.
 *
 * <pre>
 *   REGISTERED -> CANDIDATE -> PRODUCTION -> CANDIDATE (demoted by a newer promotion/rollback)
 *                  |              |
 *                  v              v
 *                ARCHIVED       (only via explicit demotion path)
 *
 *   FAILED = checksum failed / incomplete upload; never visible to readers; storage reclaimable.
 * </pre>
 */
public enum Stage {
    REGISTERED,
    CANDIDATE,
    PRODUCTION,
    ARCHIVED,
    FAILED
}
