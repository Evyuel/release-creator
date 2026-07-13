package com.dtim.releasecreator.client;

import com.dtim.releasecreator.config.TeamCityProperties;
import com.dtim.releasecreator.exception.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TeamCityClient {

    private final TeamCityProperties properties;
    private final RestClient restClient;

    public TeamCityClient(TeamCityProperties properties) {
        this.properties = properties;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (properties.token() != null && !properties.token().isBlank()) {
            builder.defaultHeaders(headers -> headers.setBearerAuth(properties.token()));
        }
        this.restClient = builder.build();
    }

    public TeamCityBuild triggerBuild(String repository, String branchName) {
        Map<String, Object> body = Map.of(
                "buildType", Map.of("id", properties.buildTypeId(repository)),
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

    private TeamCityBuild toBuild(JsonNode response) {
        if (response == null || !response.hasNonNull("id")) {
            throw new IntegrationException("TeamCity returned an empty or invalid build response");
        }
        return new TeamCityBuild(
                response.path("id").asLong(),
                response.path("state").asText(),
                response.path("status").asText(),
                response.path("webUrl").asText());
    }
}
