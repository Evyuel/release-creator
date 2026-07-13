package com.dtim.releasecreator.client;

import com.dtim.releasecreator.config.BitbucketProperties;
import com.dtim.releasecreator.exception.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class BitbucketClient {

    private static final int PAGE_SIZE = 100;

    private final BitbucketProperties properties;
    private final RestClient restClient;

    public BitbucketClient(BitbucketProperties properties) {
        this.properties = properties;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (hasText(properties.token())) {
            if (hasText(properties.username())) {
                builder.defaultHeaders(headers -> headers.setBasicAuth(properties.username(), properties.token()));
            } else {
                builder.defaultHeaders(headers -> headers.setBearerAuth(properties.token()));
            }
        }
        this.restClient = builder.build();
    }

    public List<String> getRepositoryNames() {
        List<String> repositories = new ArrayList<>();
        int start = 0;
        boolean lastPage;

        do {
            int pageStart = start;
            JsonNode page = get(uriBuilder -> uriBuilder
                    .path("/rest/api/1.0/projects/{projectKey}/repos")
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("start", pageStart)
                    .build(properties.projectKey()));

            for (JsonNode repository : page.path("values")) {
                String slug = repository.path("slug").asText();
                if (!slug.isBlank()) {
                    repositories.add(slug);
                }
            }
            lastPage = page.path("isLastPage").asBoolean(true);
            start = page.path("nextPageStart").asInt(start + PAGE_SIZE);
        } while (!lastPage);

        return List.copyOf(repositories);
    }

    public boolean branchExists(String repository, String branchName) {
        JsonNode page = get(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/branches")
                .queryParam("filterText", branchName)
                .queryParam("limit", PAGE_SIZE)
                .build(properties.projectKey(), repository));

        for (JsonNode branch : page.path("values")) {
            if (branchName.equals(branch.path("displayId").asText())
                    || ("refs/heads/" + branchName).equals(branch.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasChangesBetweenMasterAndDevelop(String repository) {
        JsonNode changes = get(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/changes")
                .queryParam("since", "refs/heads/" + properties.masterBranch())
                .queryParam("until", "refs/heads/" + properties.developBranch())
                .queryParam("limit", 1)
                .build(properties.projectKey(), repository));
        return changes.path("values").size() > 0;
    }

    public void createBranch(String repository, String branchName) {
        Map<String, Object> body = Map.of(
                "name", branchName,
                "startPoint", "refs/heads/" + properties.developBranch());
        post(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/branches")
                .build(properties.projectKey(), repository), body);
    }

    public PullRequestInfo createPullRequest(String repository, String branchName, String releaseNumber) {
        Map<String, Object> fromRef = repositoryRef(repository, "refs/heads/" + branchName);
        Map<String, Object> toRef = repositoryRef(repository, "refs/heads/" + properties.masterBranch());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Release " + releaseNumber + " for " + repository);
        body.put("description", "Automated release pull request for " + releaseNumber + ".");
        body.put("fromRef", fromRef);
        body.put("toRef", toRef);
        body.put("open", true);
        body.put("closed", false);

        JsonNode response = post(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/pull-requests")
                .build(properties.projectKey(), repository), body);
        long id = response.path("id").asLong();
        String url = response.path("links").path("self").path(0).path("href").asText();
        if (url.isBlank()) {
            url = properties.baseUrl() + "/projects/" + properties.projectKey()
                    + "/repos/" + repository + "/pull-requests/" + id;
        }
        return new PullRequestInfo(id, url);
    }

    private Map<String, Object> repositoryRef(String repository, String refId) {
        return Map.of(
                "id", refId,
                "repository", Map.of(
                        "slug", repository,
                        "project", Map.of("key", properties.projectKey())));
    }

    private JsonNode get(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri) {
        try {
            JsonNode response = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            if (response == null) {
                throw new IntegrationException("Bitbucket returned an empty response");
            }
            return response;
        } catch (RestClientException exception) {
            throw new IntegrationException("Bitbucket request failed: " + exception.getMessage(), exception);
        }
    }

    private JsonNode post(
            java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri,
            Object body) {
        try {
            JsonNode response = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new IntegrationException("Bitbucket returned an empty response");
            }
            return response;
        } catch (RestClientException exception) {
            throw new IntegrationException("Bitbucket request failed: " + exception.getMessage(), exception);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
