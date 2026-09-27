package com.github.guoyyit.modelregistryjava.web.dto;

public record RegisterResponse(VersionDto version, boolean reused, String uploadId) {
}
