package com.dtim.releasecreator.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "integrations")
public record IntegrationsProperties(List<String> excludedRepositories) {

    public IntegrationsProperties {
        excludedRepositories = excludedRepositories == null
                ? List.of()
                : excludedRepositories.stream()
                        .filter(repository -> repository != null && !repository.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }
}
