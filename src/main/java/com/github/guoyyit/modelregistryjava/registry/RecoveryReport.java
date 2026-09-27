package com.github.guoyyit.modelregistryjava.registry;

import java.util.List;

/**
 * Result of a crash-recovery sweep performed at startup.
 *
 * @param completedUploads upload markers whose content was valid and that were
 *                         finalized as fully published versions
 * @param reclaimedUploads interrupted uploads whose storage was reclaimed
 * @param suspiciousUploads interrupted uploads left on disk for manual review
 */
public record RecoveryReport(
        List<ResolvedUpload> completedUploads,
        List<ResolvedUpload> reclaimedUploads,
        List<ResolvedUpload> suspiciousUploads) {

    public record ResolvedUpload(String uploadId, String model, String version, String reason) {
    }
}
