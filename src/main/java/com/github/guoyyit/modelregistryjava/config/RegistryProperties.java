package com.github.guoyyit.modelregistryjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the model registry.
 *
 * <ul>
 *   <li>{@code registry.home}: storage root directory (blobs + journal).</li>
 *   <li>{@code registry.checksum.algorithm}: digest algorithm (sha-256, sha-1, md5).</li>
 *   <li>{@code registry.duplicate-content}: how repeated content is handled
 *       (REUSE_PUBLISHED or REJECT).</li>
 *   <li>{@code registry.recover-on-startup}: reclaim crashed-upload storage on boot.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "registry")
public class RegistryProperties {

    private String home = "data/registry";
    private Checksum checksum = new Checksum();
    private DuplicateContent duplicateContent = DuplicateContent.REUSE_PUBLISHED;
    private boolean recoverOnStartup = true;

    public static class Checksum {
        /** One of: sha-256 (default), sha-1, md5. */
        private String algorithm = "sha-256";

        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    }

    public enum DuplicateContent { REUSE_PUBLISHED, REJECT }

    public String getHome() { return home; }
    public void setHome(String home) { this.home = home; }
    public Checksum getChecksum() { return checksum; }
    public void setChecksum(Checksum checksum) { this.checksum = checksum; }
    public DuplicateContent getDuplicateContent() { return duplicateContent; }
    public void setDuplicateContent(DuplicateContent duplicateContent) {
        this.duplicateContent = duplicateContent;
    }
    public boolean isRecoverOnStartup() { return recoverOnStartup; }
    public void setRecoverOnStartup(boolean recoverOnStartup) {
        this.recoverOnStartup = recoverOnStartup;
    }
}
