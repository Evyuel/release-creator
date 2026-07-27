package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dtim.releasecreator.client.TeamCityBuild;
import com.dtim.releasecreator.client.TeamCityClient;
import com.dtim.releasecreator.dto.DeploymentStatus;
import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import com.dtim.releasecreator.exception.IntegrationException;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private ReleaseDeploymentCsvReportWriter reportWriter;

    private ReleaseDeploymentService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseDeploymentService(
                releaseValidator,
                releaseRepositoryProvider,
                teamCityClient,
                new ProductionReleaseBuildService(),
                reportWriter);
    }

    @Test
    void startsConfiguredDeploymentAndKeepsPerRepositoryFailures() {
        TeamCityBuild alphaSource = build(101, "180.0.0.15");
        TeamCityBuild gammaSource = build(103, "180.0.0.17");
        TeamCityBuild deployBuild = new TeamCityBuild(
                201, "MYPROJ_Alpha_Deploy_Uat", "1", "UNKNOWN", "queued",
                "release/180.0.0", "http://teamcity/201");
        when(releaseRepositoryProvider.getRepositoriesForRelease())
                .thenReturn(List.of("alpha", "beta", "gamma"));
        when(teamCityClient.findLatestSuccessfulBuild("Alpha_Module_Build", "release/180.0.0"))
                .thenReturn(Optional.of(alphaSource));
        when(teamCityClient.findLatestSuccessfulBuild("Beta_Module_Build", "release/180.0.0"))
                .thenReturn(Optional.empty());
        when(teamCityClient.findLatestSuccessfulBuild("Gamma_Module_Build", "release/180.0.0"))
                .thenReturn(Optional.of(gammaSource));
        when(teamCityClient.triggerUatDeployBuild(
                "Alpha_Deployment_DeployIntegration", "release/180.0.0", "alpha", "180.0.0", alphaSource))
                .thenReturn(deployBuild);
        when(teamCityClient.triggerUatDeployBuild(
                "Gamma_Deployment_DeployIntegration", "release/180.0.0", "gamma", "180.0.0", gammaSource))
                .thenThrow(new IntegrationException("deploy unavailable"));
        when(reportWriter.writeUatDeploymentReport(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Path.of("reports", "deployments", "uat-deployment-180.0.0.csv"));

        ReleaseDeploymentResult result = service.deployToUat("180.0.0");

        assertThat(result.totalServices()).isEqualTo(3);
        assertThat(result.startedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.csvReportPath()).isEqualTo("reports/deployments/uat-deployment-180.0.0.csv");
        assertThat(result.services()).extracting(service -> service.repoSlug() + ":" + service.status())
                .containsExactly("alpha:STARTED", "beta:FAILED", "gamma:FAILED");
        assertThat(result.services().get(0).sourceBuildNumber()).isEqualTo("180.0.0.15");
        assertThat(result.services().get(0).teamCityDeployBuildId()).isEqualTo(201L);
        assertThat(result.services().get(1).errorMessage())
                .isEqualTo("Successful source build not found for branch release/180.0.0");
        assertThat(result.services().get(2).sourceBuildId()).isEqualTo(103L);
        assertThat(result.services().get(2).errorMessage())
                .isEqualTo("deploy unavailable");
        verify(teamCityClient, never()).getBuild(201);
    }

    @Test
    void validationFailureStopsTheWholeEndpoint() {
        org.mockito.Mockito.doThrow(new InvalidReleaseNumberException("bad"))
                .when(releaseValidator).validateVersion("bad");

        assertThatThrownBy(() -> service.deployToUat("bad"))
                .isInstanceOf(InvalidReleaseNumberException.class);

        verifyNoInteractions(releaseRepositoryProvider, teamCityClient, reportWriter);
    }

    @Test
    void csvFailureDoesNotDiscardStartedDeployments() {
        TeamCityBuild source = build(101, "180.0.0.15");
        TeamCityBuild deployment = new TeamCityBuild(201, "queued", "UNKNOWN", "http://teamcity/201");
        when(releaseRepositoryProvider.getRepositoriesForRelease()).thenReturn(List.of("alpha"));
        when(teamCityClient.findLatestSuccessfulBuild("Alpha_Module_Build", "release/180.0.0"))
                .thenReturn(Optional.of(source));
        when(teamCityClient.triggerUatDeployBuild(
                "Alpha_Deployment_DeployIntegration", "release/180.0.0", "alpha", "180.0.0", source))
                .thenReturn(deployment);
        when(reportWriter.writeUatDeploymentReport(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("disk is full"));

        ReleaseDeploymentResult result = service.deployToUat("180.0.0");

        assertThat(result.startedCount()).isEqualTo(1);
        assertThat(result.services().get(0).status()).isEqualTo(DeploymentStatus.STARTED);
        assertThat(result.csvReportPath()).isNull();
    }

    private TeamCityBuild build(long id, String number) {
        return new TeamCityBuild(
                id,
                "MYPROJ_Source",
                number,
                "SUCCESS",
                "finished",
                "release/180.0.0",
                "http://teamcity/" + id);
    }
}
