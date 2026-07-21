package com.dtim.releasecreator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "integrations.build-name-exceptions")
public record TeamCityBuildNameExceptionProperties(
        Map<String, String> releaseProductionExceptions,
        Map<String, String> justBuildExceptions,
        Map<String, String> tst1DeployExceptions
) {
}
