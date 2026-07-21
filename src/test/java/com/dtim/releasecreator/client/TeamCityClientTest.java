package com.dtim.releasecreator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dtim.releasecreator.config.TeamCityProperties;
import com.dtim.releasecreator.service.ProductionReleaseBuildService;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TeamCityClientTest {

    private MockRestServiceServer server;
    private TeamCityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TeamCityProperties properties = new TeamCityProperties(
                URI.create("http://teamcity"),
                "secret-token",
                Duration.ofSeconds(1),
                Duration.ofHours(1));
        client = new TeamCityClient(properties, new ProductionReleaseBuildService(), builder);
    }

    @Test
    void findsLatestSuccessfulFinishedBuildForBranch() {
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/app/rest/builds");
                    String query = URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("buildType:(id:MYPROJ_calc_Release)")
                            .contains("branch:release/180.0.0")
                            .contains("status:SUCCESS")
                            .contains("state:finished")
                            .contains("count:1");
                })
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "count": 1,
                          "build": [{
                            "id": 123456,
                            "buildTypeId": "MYPROJ_calc_Release",
                            "number": "180.0.0.15",
                            "status": "SUCCESS",
                            "state": "finished",
                            "branchName": "release/180.0.0",
                            "webUrl": "http://teamcity/build/123456"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<TeamCityBuild> result =
                client.findLatestSuccessfulBuild("MYPROJ_calc_Release", "release/180.0.0");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().id()).isEqualTo(123456L);
        assertThat(result.orElseThrow().number()).isEqualTo("180.0.0.15");
        server.verify();
    }

    @Test
    void returnsEmptyWhenSuccessfulBuildDoesNotExist() {
        server.expect(method(GET))
                .andRespond(withSuccess("{\"count\":0,\"build\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findLatestSuccessfulBuild("MYPROJ_calc_Release", "release/180.0.0"))
                .isEmpty();
        server.verify();
    }

    @Test
    void queuesUatDeploymentWithSourceBuildProperties() {
        TeamCityBuild source = new TeamCityBuild(
                123456,
                "MYPROJ_calc_Release",
                "180.0.0.15",
                "SUCCESS",
                "finished",
                "release/180.0.0",
                "http://teamcity/build/123456");
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/app/rest/buildQueue"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("MYPROJ_Calc_Deploy_Uat")))
                .andExpect(content().string(containsString("env.SOURCE_BUILD_ID")))
                .andExpect(content().string(containsString("123456")))
                .andExpect(content().string(containsString("env.SOURCE_BUILD_NUMBER")))
                .andExpect(content().string(containsString("180.0.0.15")))
                .andRespond(withSuccess("""
                        {
                          "id": 456789,
                          "buildTypeId": "MYPROJ_Calc_Deploy_Uat",
                          "number": "1",
                          "status": "UNKNOWN",
                          "state": "queued",
                          "branchName": "release/180.0.0",
                          "webUrl": "http://teamcity/build/456789"
                        }
                        """, MediaType.APPLICATION_JSON));

        TeamCityBuild deployment = client.triggerUatDeployBuild(
                "MYPROJ_Calc_Deploy_Uat",
                "release/180.0.0",
                "calc",
                "180.0.0",
                source);

        assertThat(deployment.id()).isEqualTo(456789L);
        assertThat(deployment.state()).isEqualTo("queued");
        server.verify();
    }
}
