package com.github.guoyyit.modelregistryjava.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs the crash-recovery sweep once when the service starts.
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final RegistryService registryService;

    public StartupRecovery(RegistryService registryService) {
        this.registryService = registryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        RecoveryReport report = registryService.recover();
        log.info("Recovery complete: completed={}, reclaimed={}, suspicious={}",
                report.completedUploads().size(),
                report.reclaimedUploads().size(),
                report.suspiciousUploads().size());
    }
}
