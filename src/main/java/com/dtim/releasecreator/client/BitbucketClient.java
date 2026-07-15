package com.dtim.releasecreator.client;

import com.dtim.releasecreator.config.BitbucketProperties;
import com.dtim.releasecreator.exception.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.web.util.UriBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class BitbucketClient {

    private static final Logger log = LoggerFactory.getLogger(BitbucketClient.class);
    private static final int PAGE_SIZE = 100;

    private final BitbucketProperties properties;
    private final RestClient restClient;

    @Autowired
    public BitbucketClient(BitbucketProperties properties) {
        this(properties, RestClient.builder());
    }

    BitbucketClient(BitbucketProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        RestClient.Builder builder = restClientBuilder
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
        int inactiveCount = 0;
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
                if (!isActive(repository)) {
                    inactiveCount++;
                    continue;
                }
                String slug = repository.path("slug").asText();
                if (!slug.isBlank()) {
                    repositories.add(slug);
                }
            }
            lastPage = page.path("isLastPage").asBoolean(true);
            start = page.path("nextPageStart").asInt(start + PAGE_SIZE);
        } while (!lastPage);

        log.info("BITBUCKET ACTIVE REPOSITORIES LOADED | active={} inactiveFiltered={}",
                repositories.size(), inactiveCount);
        return List.copyOf(repositories);
    }

    private boolean isActive(JsonNode repository) {
        String state = repository.path("state").asText();
        boolean stateAvailable = state.isBlank() || "AVAILABLE".equalsIgnoreCase(state);
        boolean active = repository.path("active").asBoolean(true);
        boolean archived = repository.path("archived").asBoolean(false);
        return stateAvailable && active && !archived;
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
        return hasChanges(repository, properties.masterBranch(), properties.developBranch());
    }

    public boolean hasChanges(String repository, String sinceBranch, String untilBranch) {
        JsonNode changes = get(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/changes")
                .queryParam("since", ref(sinceBranch))
                .queryParam("until", ref(untilBranch))
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
        BitbucketPullRequest pullRequest = createPullRequest(
                repository,
                branchName,
                properties.masterBranch(),
                "Release " + releaseNumber + " for " + repository,
                "Automated release pull request for " + releaseNumber + ".");
        return new PullRequestInfo(pullRequest.id(), pullRequest.url());
    }

    public BitbucketPullRequest createPullRequest(
            String repository,
            String fromBranch,
            String toBranch,
            String title,
            String description) {
        Map<String, Object> fromRef = repositoryRef(repository, ref(fromBranch));
        Map<String, Object> toRef = repositoryRef(repository, ref(toBranch));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("fromRef", fromRef);
        body.put("toRef", toRef);
        body.put("open", true);
        body.put("closed", false);

        JsonNode response = post(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/pull-requests")
                .build(properties.projectKey(), repository), body);
        return toPullRequest(repository, response);
    }

    public List<BitbucketPullRequest> findPullRequests(
            String repository,
            String fromBranch,
            String toBranch) {
        List<BitbucketPullRequest> pullRequests = new ArrayList<>();
        int start = 0;
        boolean lastPage;
        String expectedFromRef = ref(fromBranch);
        String expectedToRef = ref(toBranch);
        do {
            int pageStart = start;
            JsonNode page = get(uriBuilder -> uriBuilder
                    .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/pull-requests")
                    .queryParam("state", "ALL")
                    .queryParam("at", expectedToRef)
                    .queryParam("direction", "INCOMING")
                    .queryParam("limit", PAGE_SIZE)
                    .queryParam("start", pageStart)
                    .build(properties.projectKey(), repository));
            for (JsonNode pullRequest : page.path("values")) {
                BitbucketPullRequest parsed = toPullRequest(repository, pullRequest);
                if (expectedFromRef.equals(parsed.fromRef()) && expectedToRef.equals(parsed.toRef())) {
                    pullRequests.add(parsed);
                }
            }
            lastPage = page.path("isLastPage").asBoolean(true);
            start = page.path("nextPageStart").asInt(start + PAGE_SIZE);
        } while (!lastPage);
        return List.copyOf(pullRequests);
    }

    public BitbucketPullRequest mergePullRequest(String repository, BitbucketPullRequest pullRequest) {
        JsonNode mergeability = get(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/pull-requests/{pullRequestId}/merge")
                .build(properties.projectKey(), repository, pullRequest.id()));
        if (!mergeability.path("canMerge").asBoolean(false)) {
            String vetoes = mergeability.path("vetoes").findValuesAsText("summaryMessage").stream()
                    .filter(message -> !message.isBlank())
                    .distinct()
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new IntegrationException("Pull request " + pullRequest.id() + " cannot be merged"
                    + (vetoes.isBlank() ? "" : ": " + vetoes));
        }
        JsonNode response = post(uriBuilder -> uriBuilder
                .path("/rest/api/1.0/projects/{projectKey}/repos/{repository}/pull-requests/{pullRequestId}/merge")
                .queryParam("version", pullRequest.version())
                .build(properties.projectKey(), repository, pullRequest.id()), Map.of());
        return toPullRequest(repository, response);
    }

    private Map<String, Object> repositoryRef(String repository, String refId) {
        return Map.of(
                "id", refId,
                "repository", Map.of(
                        "slug", repository,
                        "project", Map.of("key", properties.projectKey())));
    }

    private BitbucketPullRequest toPullRequest(String repository, JsonNode response) {
        long id = response.path("id").asLong();
        String url = response.path("links").path("self").path(0).path("href").asText();
        if (url.isBlank()) {
            url = properties.baseUrl() + "/projects/" + properties.projectKey()
                    + "/repos/" + repository + "/pull-requests/" + id;
        }
        return new BitbucketPullRequest(
                id,
                response.path("version").asInt(),
                response.path("state").asText(),
                response.path("fromRef").path("id").asText(),
                response.path("toRef").path("id").asText(),
                url);
    }

    private String ref(String branch) {
        return branch.startsWith("refs/") ? branch : "refs/heads/" + branch;
    }

    private JsonNode get(Function<UriBuilder, java.net.URI> uri) {
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
            Function<UriBuilder, java.net.URI> uri,
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
