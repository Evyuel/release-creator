package com.dtim.releasecreator.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "integrations.teamcity")
public record TeamCityProperties(
        @NotNull URI baseUrl,
        String token,
        @NotNull Duration pollInterval,
        @NotNull Duration waitTimeout,
        Map<String, String> uatDeployBuildTypeByRepo) {

    public TeamCityProperties {
        uatDeployBuildTypeByRepo = uatDeployBuildTypeByRepo == null
                ? Map.of()
                : Map.copyOf(uatDeployBuildTypeByRepo);
    }

    public Optional<String> uatDeployBuildTypeId(String repository) {
        return Optional.ofNullable(uatDeployBuildTypeByRepo.get(repository))
                .filter(value -> !value.isBlank());
    }
}
