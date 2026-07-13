package com.dtim.releasecreator.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ProductionReleaseBuildService {

    /**
     * Some Release Production build configurations do not follow the common naming convention.
     */
    private static final Map<String, String> REL_PROD_EXCEPTIONS = Map.of(
            "service1", "ser1_ReleaseProduction",
            "service2", "service_2_ReleaseProduction");

    private static final Map<String, String> BUILD_EXCEPTIONS = Map.of(
            "service1", "ser1_Build",
            "service2", "service_2_Build");

    private static final Map<String, String> TST1_DEPLOY_EXCEPTIONS = Map.of(
            "service1", "ser1_DeployTST1",
            "service2", "service_2_DeployTST1");

    public String getReleaseProductionTypeForRepo(String repository) {
        if (REL_PROD_EXCEPTIONS.containsKey(repository)) {
            return REL_PROD_EXCEPTIONS.get(repository);
        }
        return String.format("%s_Deployment_ReleaseProduction", toPascalCase(repository));
    }
    public String getBuildTypeForRepo(String repository) {
        if (BUILD_EXCEPTIONS.containsKey(repository)) {
            return BUILD_EXCEPTIONS.get(repository);
        }
        return String.format("%s_Module_Build", toPascalCase(repository));
    }

    public String getTST1DeployTypeForRepo(String repository) {
        if (TST1_DEPLOY_EXCEPTIONS.containsKey(repository)) {
            return TST1_DEPLOY_EXCEPTIONS.get(repository);
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
