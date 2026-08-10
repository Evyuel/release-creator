package com.dtim.releasecreator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dtim.releasecreator.config.BitbucketProperties;
import com.dtim.releasecreator.exception.IntegrationException;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BitbucketClientTest {

    private MockRestServiceServer server;
    private BitbucketClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BitbucketClient(
                new BitbucketProperties(
                        URI.create("http://bitbucket"), "", "", "MYPROJ", "develop", "master"),
                builder,
                new BitbucketRequestExecutor(RateLimiterRegistry.ofDefaults()));
    }

    @Test
    void returnsOnlyActiveAvailableNonArchivedRepositories() {
        server.expect(method(GET)).andRespond(withSuccess("""
                {
                  "isLastPage": true,
                  "values": [
                    {"slug":"active", "state":"AVAILABLE", "active":true, "archived":false},
                    {"slug":"inactive", "state":"AVAILABLE", "active":false},
                    {"slug":"archived", "state":"AVAILABLE", "archived":true},
                    {"slug":"unavailable", "state":"INITIALISATION_FAILED", "active":true},
                    {"slug":"legacy-without-flags"}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getRepositoryNames()).containsExactly("active", "legacy-without-flags");
        server.verify();
    }

    @Test
    void findsOnlyPullRequestsWithExactSourceAndTargetBranches() {
        server.expect(method(GET)).andRespond(withSuccess("""
                {
                  "isLastPage": true,
                  "values": [
                    {"id":10,"version":2,"state":"OPEN",
                     "fromRef":{"id":"refs/heads/release/181.0.0"},
                     "toRef":{"id":"refs/heads/master"}},
                    {"id":11,"version":1,"state":"OPEN",
                     "fromRef":{"id":"refs/heads/release/180.0.0"},
                     "toRef":{"id":"refs/heads/master"}}
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        List<BitbucketPullRequest> result =
                client.findPullRequests("orders", "release/181.0.0", "master");

        assertThat(result).extracting(BitbucketPullRequest::id).containsExactly(10L);
        assertThat(result.get(0).url()).contains("/repos/orders/pull-requests/10");
        server.verify();
    }

    @Test
    void checksMergeabilityBeforeMergingPullRequest() {
        BitbucketPullRequest pullRequest = new BitbucketPullRequest(
                10, 2, "OPEN", "refs/heads/release/181.0.0", "refs/heads/master", "url");
        server.expect(method(GET)).andRespond(withSuccess("{\"canMerge\":true}", MediaType.APPLICATION_JSON));
        server.expect(method(POST)).andRespond(withSuccess("""
                {"id":10,"version":3,"state":"MERGED",
                 "fromRef":{"id":"refs/heads/release/181.0.0"},
                 "toRef":{"id":"refs/heads/master"}}
                """, MediaType.APPLICATION_JSON));

        BitbucketPullRequest merged = client.mergePullRequest("orders", pullRequest);

        assertThat(merged.merged()).isTrue();
        assertThat(merged.version()).isEqualTo(3);
        server.verify();
    }

    @Test
    void doesNotPostMergeWhenBitbucketReturnsAVeto() {
        BitbucketPullRequest pullRequest = new BitbucketPullRequest(
                10, 2, "OPEN", "refs/heads/release/181.0.0", "refs/heads/master", "url");
        server.expect(method(GET)).andRespond(withSuccess("""
                {"canMerge":false,"vetoes":[{"summaryMessage":"Build is not successful"}]}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.mergePullRequest("orders", pullRequest))
                .isInstanceOf(IntegrationException.class)
                .hasMessageContaining("Build is not successful");
        server.verify();
    }
}
