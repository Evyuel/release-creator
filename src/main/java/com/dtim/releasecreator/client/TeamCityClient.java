package com.dtim.releasecreator.client;

import com.dtim.releasecreator.config.TeamCityProperties;
import com.dtim.releasecreator.exception.IntegrationException;
import com.dtim.releasecreator.service.ProductionReleaseBuildService;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TeamCityClient {
    private final ProductionReleaseBuildService productionReleaseBuildService;
    private final RestClient restClient;

    @Autowired
    public TeamCityClient(
            TeamCityProperties properties,
            ProductionReleaseBuildService productionReleaseBuildService) {
        this(properties, productionReleaseBuildService, defaultRestClientBuilder());
    }

    TeamCityClient(
            TeamCityProperties properties,
            ProductionReleaseBuildService productionReleaseBuildService,
            RestClient.Builder restClientBuilder) {
        this.productionReleaseBuildService = productionReleaseBuildService;

        RestClient.Builder builder = restClientBuilder
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (properties.token() != null && !properties.token().isBlank()) {
            builder.defaultHeaders(headers -> headers.setBearerAuth(properties.token()));
        }
        this.restClient = builder.build();
    }

    private static RestClient.Builder defaultRestClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(30));
        requestFactory.setReadTimeout(Duration.ofMinutes(10));
        return RestClient.builder().requestFactory(requestFactory);
    }

    public TeamCityBuild triggerBuild(String repository, String branchName) {
        Map<String, Object> body = Map.of(
                "buildType", Map.of("id", productionReleaseBuildService.getReleaseProductionTypeForRepo(repository)),
                "branchName", branchName);
        try {
            JsonNode response = restClient.post()
                    .uri("/app/rest/buildQueue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return toBuild(response);
        } catch (RestClientException exception) {
            throw new IntegrationException("TeamCity build queue request failed: " + exception.getMessage(), exception);
        }
    }

    public TeamCityBuild getBuild(long buildId) {
        try {
            JsonNode response = restClient.get()
                    .uri("/app/rest/builds/id:{buildId}", buildId)
                    .retrieve()
                    .body(JsonNode.class);
            return toBuild(response);
        } catch (RestClientException exception) {
            throw new IntegrationException(
                    "TeamCity build status request failed for build " + buildId + ": " + exception.getMessage(),
                    exception);
        }
    }

    public Optional<TeamCityBuild> findLatestSuccessfulBuild(String buildTypeId, String branchName) {
        String locator = "buildType:(id:" + buildTypeId + "),branch:" + branchName
                + ",status:SUCCESS,state:finished,count:1";
        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/app/rest/builds")
                            .queryParam("locator", locator)
                            .queryParam("fields", "build(id,buildTypeId,number,status,state,branchName,webUrl)")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("build").isArray() || response.path("build").isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toBuild(response.path("build").get(0)));
        } catch (RestClientException exception) {
            throw new IntegrationException(
                    "TeamCity successful build lookup failed for buildType " + buildTypeId
                            + " and branch " + branchName + ": " + exception.getMessage(),
                    exception);
        }
    }

    public TeamCityBuild triggerUatDeployBuild(
            String deployBuildTypeId,
            String repository,
            String releaseVersion,
            TeamCityBuild sourceBuild) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("buildType", Map.of("id", deployBuildTypeId));
        body.put("comment", Map.of(
                "text", "Deploy " + repository + " release " + releaseVersion + " to UAT"));
        body.put("properties", Map.of("property", List.of(
                teamCityProperty("ansible_version_tag", sourceBuild.number())
        )));
        try {
            JsonNode response = restClient.post()
                    .uri("/app/rest/buildQueue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return toBuild(response);
        } catch (RestClientException exception) {
            throw new IntegrationException(
                    "TeamCity UAT deployment queue request failed for repository " + repository
                            + ": " + exception.getMessage(),
                    exception);
        }
    }

    private Map<String, String> teamCityProperty(String name, String value) {
        return Map.of("name", name, "value", value == null ? "" : value);
    }

    private TeamCityBuild toBuild(JsonNode response) {
        if (response == null || !response.hasNonNull("id")) {
            throw new IntegrationException("TeamCity returned an empty or invalid build response");
        }
        return new TeamCityBuild(
                response.path("id").asLong(),
                response.path("buildTypeId").asText(null),
                response.path("number").asText(null),
                response.path("status").asText(),
                response.path("state").asText(),
                response.path("branchName").asText(null),
                response.path("webUrl").asText());
    }
}
