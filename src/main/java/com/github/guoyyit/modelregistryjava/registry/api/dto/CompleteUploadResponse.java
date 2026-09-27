package com.github.guoyyit.modelregistryjava.registry.api.dto;

import com.github.guoyyit.modelregistryjava.registry.VersionMetadata;

public record CompleteUploadResponse(boolean duplicate, VersionMetadata version) {
}
