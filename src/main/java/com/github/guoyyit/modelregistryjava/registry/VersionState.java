package com.github.guoyyit.modelregistryjava.registry;

/**
 * Lifecycle stage of a single model version.
 *
 * <pre>
 * REGISTERED -> CANDIDATE -> PRODUCTION -> RETIRED
 * </pre>
 *
 * Stage advances forward only. The only way to move a version "back" is an
 * explicit rollback that promotes a historical version to PRODUCTION; the
 * version that loses PRODUCTION is moved to RETIRED rather than demoted in
 * place.
 */
public enum VersionState {
    /** Fully published, not yet promoted. */
    REGISTERED,
    /** Promoted as a release candidate. */
    CANDIDATE,
    /** The version currently serving in production. */
    PRODUCTION,
    /** Deactivated/offline. Metadata and audit history are retained. */
    RETIRED
}
