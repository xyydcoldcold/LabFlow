package com.labflow.backend.artifact;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ArtifactStorageProperties.class)
public class ArtifactStorageConfiguration {

    @Bean
    ArtifactStorage artifactStorage(ArtifactStorageProperties properties) {
        return new FileSystemArtifactStorage(properties.root());
    }
}
