package com.github.guoyyit.modelregistryjava.service;

import com.github.guoyyit.modelregistryjava.domain.AuditEvent;
import com.github.guoyyit.modelregistryjava.domain.ModelVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory state rebuilt by replaying the event journal.
 * Keyed by (model, version). Only mutated by the service while holding the model lock.
 */
final class ModelRegistryState {

    /** model -> (version -> version state) */
    final Map<String, Map<Long, ModelVersion>> versions = new LinkedHashMap<>();
    /** model -> version number most recently allocated (including unfinished uploads) */
    final Map<String, Long> lastAllocated = new LinkedHashMap<>();
    /** model -> current production version */
    final Map<String, Long> production = new LinkedHashMap<>();
    /** model -> checksum -> version of an existing non-archived version (dedup index) */
    final Map<String, Map<String, Long>> checksumIndex = new LinkedHashMap<>();
    /** model -> ordered audit events */
    final Map<String, List<AuditEvent>> audit = new LinkedHashMap<>();
    /** insertion-ordered set of all models */
    final List<String> models = new ArrayList<>();

    Map<Long, ModelVersion> versionsOf(String model) {
        return versions.computeIfAbsent(model, m -> {
            models.add(m);
            return new LinkedHashMap<>();
        });
    }
}
