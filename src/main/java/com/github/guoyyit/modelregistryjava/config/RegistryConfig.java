package com.github.guoyyit.modelregistryjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RegistryProperties.class)
public class RegistryConfig {
}
