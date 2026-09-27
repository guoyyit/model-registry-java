package com.github.guoyyit.modelregistryjava.registry.api.dto;

public record RollbackRequest(String targetVersion, String reason) {
}
