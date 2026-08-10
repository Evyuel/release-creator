package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dtim.releasecreator.client.TeamCityBuild;
import com.dtim.releasecreator.client.TeamCityClient;
import com.dtim.releasecreator.config.TeamCityProperties;
import com.dtim.releasecreator.dto.DeploymentStatus;
import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReleaseDeploymentServiceTest {

    @Mock
    private ReleaseValidator releaseValidator;
    @Mock
    private ReleaseRepositoryProvider releaseRepositoryProvider;
    @Mock
    private TeamCityClient teamCityClient;
    @Mock
    private ProductionReleaseBuildService productionReleaseBuildService;
    @Mock
    private ReleaseDeploymentCsvReportWriter reportWriter;

    private ReleaseDeploymentService service;

    @BeforeEach
    void setUp() {
        service = serviceWithTimeout(Duration.ofSeconds(1));
        org.mockito.Mockito.lenient().when(productionReleaseBuildService.getBuildTypeForRepo(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "_Build");
        org.mockito.Mockito.lenient().when(productionReleaseBuildService.getTST1DeployTypeForRepo(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0) + "_DeployUat");
        org.mockito.Mockito.lenient().when(reportWriter.writeUatDeploymentReport(any()))
                .thenReturn(Path.of("reports", "deployments", "uat-deployment-180.0.0.csv"));
    }

    @Test
    void deploysStagesInRequiredOrderAndWaitsForEachStage() {
        List<String> repositories = List.of(
                "front-service", "other-b", "gateway", "config-server",
                "business-settings-api", "other-a");
        Map<String, Long> deployIds = new LinkedHashMap<>();
        deployIds.put("config-server", 201L);
        deployIds.put("business-settings-api", 202L);
        deployIds.put("other-b", 203L);
        deployIds.put("other-a", 204L);
        deployIds.put("gateway", 205L);
        deployIds.put("front-service", 206L);
        when(releaseRepositoryProvider.getRepositoriesForRelease()).thenReturn(repositories);
        for (Map.Entry<String, Long> entry : deployIds.entrySet()) {
            String repository = entry.getKey();
            long deployId = entry.getValue();
            TeamCityBuild source = sourceBuild(deployId + 1000, repository);
            TeamCityBuild queued = deployBuild(deployId, "queued", "UNKNOWN");
            TeamCityBuild finished = deployBuild(deployId, "finished", "SUCCESS");
            when(teamCityClient.findLatestSuccessfulBuild(repository + "_Build", "release/180.0.0"))
                    .thenReturn(Optional.of(source));
            when(teamCityClient.triggerUatDeployBuild(
                    repository + "_DeployUat", repository, "180.0.0", source))
                    .thenReturn(queued);
            if (repository.equals("config-server")) {
                when(teamCityClient.getBuild(deployId))
                        .thenReturn(deployBuild(deployId, "running", "UNKNOWN"), finished);
            } else {
                when(teamCityClient.getBuild(deployId)).thenReturn(finished);
            }
        }

        ReleaseDeploymentResult result = service.deployToUat("180.0.0");

        assertThat(result.totalServices()).isEqualTo(6);
        assertThat(result.startedCount()).isEqualTo(6);
        assertThat(result.successfulCount()).isEqualTo(6);
        assertThat(result.failedCount()).isZero();
        assertThat(result.services()).extracting(service -> service.repoSlug() + ":" + service.status())
                .containsExactly(
                        "config-server:SUCCESS",
                        "business-settings-api:SUCCESS",
                        "other-b:SUCCESS",
                        "other-a:SUCCESS",
                        "gateway:SUCCESS",
                        "front-service:SUCCESS");

        InOrder order = inOrder(teamCityClient);
        order.verify(teamCityClient).triggerUatDeployBuild(anyString(),
                org.mockito.ArgumentMatchers.eq("config-server"), anyString(), any());
        order.verify(teamCityClient, times(2)).getBuild(201);
        order.verify(teamCityClient).triggerUatDeployBuild(anyString(),
                org.mockito.ArgumentMatchers.eq("business-settings-api"), anyString(), any());
        order.verify(teamCityClient).getBuild(202);
        order.verify(teamCityClient).triggerUatDeployBuild(anyString(),
                org.mockito.ArgumentMatchers.eq("other-b"), anyString(), any());
        order.verify(teamCityClient).triggerUatDeployBuild(anyString(),
                org.mockito.ArgumentMatchers.eq("other-a"), anyString(), any());
        order.verify(teamCityClient).getBuild(203);
        order.verify(teamCityClient).getBuild(204);
        order.verify(teamCityClient).triggerUatDeployBuild(anyString(),
                org.mockito.ArgumentMatchers.eq("gateway"), anyString(), any());
        order.verify(teamCityClient).getBuild(205);
        order.verify(teamCityClient).triggerUatDeployBuild(anyString(),
                org.mockito.ArgumentMatchers.eq("front-service"), anyString(), any());
        order.verify(teamCityClient).getBuild(206);
    }

    @Test
    void continuesWithNextStageAfterDeploymentFailure() {
        TeamCityBuild configSource = sourceBuild(101, "config-server");
        TeamCityBuild gatewaySource = sourceBuild(102, "gateway");
        when(releaseRepositoryProvider.getRepositoriesForRelease())
                .thenReturn(List.of("gateway", "config-server"));
        when(teamCityClient.findLatestSuccessfulBuild("config-server_Build", "release/180.0.0"))
                .thenReturn(Optional.of(configSource));
        when(teamCityClient.findLatestSuccessfulBuild("gateway_Build", "release/180.0.0"))
                .thenReturn(Optional.of(gatewaySource));
        when(teamCityClient.triggerUatDeployBuild(
                "config-server_DeployUat", "config-server", "180.0.0", configSource))
                .thenReturn(deployBuild(201, "queued", "UNKNOWN"));
        when(teamCityClient.triggerUatDeployBuild(
                "gateway_DeployUat", "gateway", "180.0.0", gatewaySource))
                .thenReturn(deployBuild(202, "queued", "UNKNOWN"));
        when(teamCityClient.getBuild(201)).thenReturn(deployBuild(201, "finished", "FAILURE"));
        when(teamCityClient.getBuild(202)).thenReturn(deployBuild(202, "finished", "SUCCESS"));

        ReleaseDeploymentResult result = service.deployToUat("180.0.0");

        assertThat(result.startedCount()).isEqualTo(2);
        assertThat(result.successfulCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.services()).extracting(service -> service.repoSlug() + ":" + service.status())
                .containsExactly("config-server:FAILED", "gateway:SUCCESS");
        assertThat(result.services().get(0).teamCityDeployBuildId()).isEqualTo(201L);
        assertThat(result.services().get(0).errorMessage()).contains("FAILURE");
    }

    @Test
    void marksQueuedDeploymentAsFailedOnTimeout() {
        service = serviceWithTimeout(Duration.ZERO);
        TeamCityBuild source = sourceBuild(101, "orders");
        when(releaseRepositoryProvider.getRepositoriesForRelease()).thenReturn(List.of("orders"));
        when(teamCityClient.findLatestSuccessfulBuild("orders_Build", "release/180.0.0"))
                .thenReturn(Optional.of(source));
        when(teamCityClient.triggerUatDeployBuild(
                "orders_DeployUat", "orders", "180.0.0", source))
                .thenReturn(deployBuild(201, "queued", "UNKNOWN"));

        ReleaseDeploymentResult result = service.deployToUat("180.0.0");

        assertThat(result.startedCount()).isEqualTo(1);
        assertThat(result.successfulCount()).isZero();
        assertThat(result.services().get(0).status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(result.services().get(0).errorMessage()).contains("Timed out");
    }

    @Test
    void csvFailureDoesNotDiscardCompletedDeployments() {
        TeamCityBuild source = sourceBuild(101, "orders");
        when(releaseRepositoryProvider.getRepositoriesForRelease()).thenReturn(List.of("orders"));
        when(teamCityClient.findLatestSuccessfulBuild("orders_Build", "release/180.0.0"))
                .thenReturn(Optional.of(source));
        when(teamCityClient.triggerUatDeployBuild(
                "orders_DeployUat", "orders", "180.0.0", source))
                .thenReturn(deployBuild(201, "queued", "UNKNOWN"));
        when(teamCityClient.getBuild(201)).thenReturn(deployBuild(201, "finished", "SUCCESS"));
        when(reportWriter.writeUatDeploymentReport(any())).thenThrow(new IllegalStateException("disk is full"));

        ReleaseDeploymentResult result = service.deployToUat("180.0.0");

        assertThat(result.successfulCount()).isEqualTo(1);
        assertThat(result.services().get(0).status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(result.csvReportPath()).isNull();
    }

    @Test
    void validationFailureStopsTheWholeEndpoint() {
        org.mockito.Mockito.doThrow(new InvalidReleaseNumberException("bad"))
                .when(releaseValidator).validateVersion("bad");

        assertThatThrownBy(() -> service.deployToUat("bad"))
                .isInstanceOf(InvalidReleaseNumberException.class);

        verifyNoInteractions(releaseRepositoryProvider, teamCityClient, reportWriter);
    }

    private ReleaseDeploymentService serviceWithTimeout(Duration timeout) {
        TeamCityProperties properties = new TeamCityProperties(
                URI.create("http://teamcity"), "token", Duration.ofMillis(1), timeout);
        return new ReleaseDeploymentService(
                releaseValidator,
                releaseRepositoryProvider,
                teamCityClient,
                properties,
                productionReleaseBuildService,
                reportWriter);
    }

    private TeamCityBuild sourceBuild(long id, String repository) {
        return new TeamCityBuild(
                id,
                repository + "_Build",
                "180.0.0.15",
                "SUCCESS",
                "finished",
                "release/180.0.0",
                "http://teamcity/source/" + id);
    }

    private TeamCityBuild deployBuild(long id, String state, String status) {
        return new TeamCityBuild(
                id,
                "Deploy_Uat",
                "1",
                status,
                state,
                null,
                "http://teamcity/deploy/" + id);
    }
}
