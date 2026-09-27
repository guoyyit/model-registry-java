package com.github.guoyyit.modelregistryjava.registry.config;

import com.github.guoyyit.modelregistryjava.registry.storage.ChecksumStrategy;
import com.github.guoyyit.modelregistryjava.registry.storage.MessageDigestChecksum;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RegistryProperties.class)
public class RegistryConfig {

    @Bean
    public ChecksumStrategy checksumStrategy(RegistryProperties properties) {
        return new MessageDigestChecksum(properties.getChecksumAlgorithm());
    }
}
