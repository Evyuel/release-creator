package com.dtim.releasecreator.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.dtim.releasecreator.config.TeamCityBuildNameExceptionProperties;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE,makeFinal = true)
@RequiredArgsConstructor
public class ProductionReleaseBuildService {
    TeamCityBuildNameExceptionProperties teamCityBuildNameExceptionProperties;

    public String getReleaseProductionTypeForRepo(String repository) {
        if (teamCityBuildNameExceptionProperties.releaseProductionExceptions().containsKey(repository)) {
            return teamCityBuildNameExceptionProperties.releaseProductionExceptions().get(repository);
        }
        return String.format("%s_Deployment_ReleaseProduction", toPascalCase(repository));
    }
    public String getBuildTypeForRepo(String repository) {
        if (teamCityBuildNameExceptionProperties.justBuildExceptions().containsKey(repository)) {
            return teamCityBuildNameExceptionProperties.justBuildExceptions().get(repository);
        }
        return String.format("%s_Module_Build", toPascalCase(repository));
    }

    public String getTST1DeployTypeForRepo(String repository) {
        if (teamCityBuildNameExceptionProperties.tst1DeployExceptions().containsKey(repository)) {
            return teamCityBuildNameExceptionProperties.tst1DeployExceptions().get(repository);
        }
        return String.format("%s_Deployment_DeployIntegration", toPascalCase(repository));
    }

    private String toPascalCase(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return Arrays.stream(value.split("[-_\\s]+"))
                .filter(part -> !part.isBlank())
                .map(this::capitalize)
                .collect(Collectors.joining());
    }

    private String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
