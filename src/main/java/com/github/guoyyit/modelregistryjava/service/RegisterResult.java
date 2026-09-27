package com.github.guoyyit.modelregistryjava.service;

import com.github.guoyyit.modelregistryjava.domain.ModelVersion;

/** Result of a registration: the effective version and whether an existing one was reused. */
public record RegisterResult(ModelVersion version, boolean reused) {
}
