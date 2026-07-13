package com.dtim.releasecreator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dtim.releasecreator.config.BitbucketProperties;
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
        client = new BitbucketClient(new BitbucketProperties(
                URI.create("http://bitbucket"), "", "", "MYPROJ", "develop", "master"), builder);
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
}
