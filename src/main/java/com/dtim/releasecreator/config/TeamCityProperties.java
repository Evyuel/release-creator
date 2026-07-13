package com.dtim.releasecreator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "integrations.teamcity")
public record TeamCityProperties(
        @NotNull URI baseUrl,
        String token,
        @NotBlank String buildTypeIdPattern,
        @NotNull Duration pollInterval,
        @NotNull Duration waitTimeout) {

    public String buildTypeId(String repository) {
        return buildTypeIdPattern.replace("{repository}", repository.replace('-', '_'));
    }
}
