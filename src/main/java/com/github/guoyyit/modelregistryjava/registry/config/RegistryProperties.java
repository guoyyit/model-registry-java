package com.github.guoyyit.modelregistryjava.registry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the local model registry.
 */
@ConfigurationProperties(prefix = "registry")
public class RegistryProperties {

    /** Root directory for all registry data (metadata, content, journal). */
    private String storagePath = "data/registry";

    /** Checksum algorithm required at registration time, e.g. SHA-256. */
    private String checksumAlgorithm = "SHA-256";

    /** Whether interrupted uploads are reclaimed automatically at startup. */
    private boolean reclaimIncompleteUploads = true;

    /** Whether content bytes of retired versions are kept (metadata always kept). */
    private boolean retainRetiredContent = true;

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getChecksumAlgorithm() {
        return checksumAlgorithm;
    }

    public void setChecksumAlgorithm(String checksumAlgorithm) {
        this.checksumAlgorithm = checksumAlgorithm;
    }

    public boolean isReclaimIncompleteUploads() {
        return reclaimIncompleteUploads;
    }

    public void setReclaimIncompleteUploads(boolean reclaimIncompleteUploads) {
        this.reclaimIncompleteUploads = reclaimIncompleteUploads;
    }

    public boolean isRetainRetiredContent() {
        return retainRetiredContent;
    }

    public void setRetainRetiredContent(boolean retainRetiredContent) {
        this.retainRetiredContent = retainRetiredContent;
    }
}
