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
    private static final Map<String, String> EXCEPTIONS = Map.of(
            "service1", "ser1_ReleaseProduction",
            "service2", "service_2_ReleaseProduction");

    public String getBuildTypeForRepo(String repository) {
        if (EXCEPTIONS.containsKey(repository)) {
            return EXCEPTIONS.get(repository);
        }
        return String.format("%s_Deployment_ReleaseProduction", toPascalCase(repository));
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
