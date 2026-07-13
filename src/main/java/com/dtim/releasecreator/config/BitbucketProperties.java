package com.dtim.releasecreator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "integrations.bitbucket")
public record BitbucketProperties(
        @NotNull URI baseUrl,
        String username,
        String token,
        @NotBlank String projectKey,
        @NotBlank String developBranch,
        @NotBlank String masterBranch) {
}
