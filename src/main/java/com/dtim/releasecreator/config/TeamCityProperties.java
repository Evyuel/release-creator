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
        @NotNull Duration waitTimeout) {
}
