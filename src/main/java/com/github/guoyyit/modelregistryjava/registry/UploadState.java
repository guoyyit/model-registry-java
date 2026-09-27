package com.github.guoyyit.modelregistryjava.registry;

/**
 * State of an in-progress upload as recorded by its durable marker.
 */
public enum UploadState {
    /** Content bytes are (possibly partially) on disk. */
    UPLOADING,
    /** Content fully written, checksum verification pending/complete. */
    FINALIZING,
    /** Metadata has been published; the upload is effectively complete. */
    PUBLISHED
}
