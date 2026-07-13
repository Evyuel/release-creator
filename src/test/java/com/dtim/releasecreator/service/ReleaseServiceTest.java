package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.client.PullRequestInfo;
import com.dtim.releasecreator.client.TeamCityBuild;
import com.dtim.releasecreator.client.TeamCityClient;
import com.dtim.releasecreator.config.TeamCityProperties;
import com.dtim.releasecreator.dto.ReleaseResult;
import com.dtim.releasecreator.dto.ReleaseStatus;
import com.dtim.releasecreator.dto.RepositoryReleaseStatus;
import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import com.dtim.releasecreator.exception.ReleaseBranchConflictException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReleaseServiceTest {

    @Mock
    private BitbucketClient bitbucketClient;

    @Mock
    private TeamCityClient teamCityClient;

    private ReleaseService releaseService;

    @BeforeEach
    void setUp() {
        TeamCityProperties properties = new TeamCityProperties(
                URI.create("http://teamcity"),
                "token",
                "MYPROJ_{repository}_Release",
                Duration.ofMillis(1),
                Duration.ofSeconds(2));
        releaseService = new ReleaseService(bitbucketClient, teamCityClient, properties);
    }

    @Test
    void rejectsInvalidReleaseNumberBeforeCallingIntegrations() {
        assertThatThrownBy(() -> releaseService.createRelease("18.0"))
                .isInstanceOf(InvalidReleaseNumberException.class);

        verifyNoInteractions(bitbucketClient, teamCityClient);
    }

    @Test
    void abortsEntireReleaseWhenBranchAlreadyExists() {
        when(bitbucketClient.getRepositoryNames()).thenReturn(List.of("orders", "billing"));
        when(bitbucketClient.branchExists("billing", "release/180.0.0")).thenReturn(true);

        assertThatThrownBy(() -> releaseService.createRelease("180.0.0"))
                .isInstanceOf(ReleaseBranchConflictException.class)
                .satisfies(exception -> assertThat(((ReleaseBranchConflictException) exception).getRepositories())
                        .containsExactly("billing"));

        verify(bitbucketClient, never()).createBranch("orders", "release/180.0.0");
        verifyNoInteractions(teamCityClient);
    }

    @Test
    void skipsRepositoryWithoutChangesAndCompletesSuccessfulBuild() {
        when(bitbucketClient.getRepositoryNames()).thenReturn(List.of("orders", "billing"));
        when(bitbucketClient.hasChangesBetweenMasterAndDevelop("billing")).thenReturn(false);
        when(bitbucketClient.hasChangesBetweenMasterAndDevelop("orders")).thenReturn(true);
        when(bitbucketClient.createPullRequest("orders", "release/180.0.0", "180.0.0"))
                .thenReturn(new PullRequestInfo(42, "http://bitbucket/pr/42"));
        when(teamCityClient.triggerBuild("orders", "release/180.0.0"))
                .thenReturn(new TeamCityBuild(100, "queued", "UNKNOWN", "http://teamcity/100"));
        when(teamCityClient.getBuild(100))
                .thenReturn(new TeamCityBuild(100, "finished", "SUCCESS", "http://teamcity/100"));

        ReleaseResult result = releaseService.createRelease("180.0.0");

        assertThat(result.status()).isEqualTo(ReleaseStatus.SUCCESS);
        assertThat(result.repositories())
                .extracting(repository -> repository.repository() + ":" + repository.status())
                .containsExactly(
                        "billing:" + RepositoryReleaseStatus.SKIPPED_NO_CHANGES,
                        "orders:" + RepositoryReleaseStatus.BUILD_SUCCESS);
        assertThat(result.repositories().get(1).pullRequestId()).isEqualTo(42);
        assertThat(result.repositories().get(1).buildIds()).containsExactly(100L);
    }

    @Test
    void retriesFailedBuildExactlyOnce() {
        when(bitbucketClient.getRepositoryNames()).thenReturn(List.of("orders"));
        when(bitbucketClient.hasChangesBetweenMasterAndDevelop("orders")).thenReturn(true);
        when(bitbucketClient.createPullRequest("orders", "release/180.0.0", "180.0.0"))
                .thenReturn(new PullRequestInfo(42, "http://bitbucket/pr/42"));
        when(teamCityClient.triggerBuild("orders", "release/180.0.0"))
                .thenReturn(
                        new TeamCityBuild(100, "queued", "UNKNOWN", "http://teamcity/100"),
                        new TeamCityBuild(101, "queued", "UNKNOWN", "http://teamcity/101"));
        when(teamCityClient.getBuild(100))
                .thenReturn(new TeamCityBuild(100, "finished", "FAILURE", "http://teamcity/100"));
        when(teamCityClient.getBuild(101))
                .thenReturn(new TeamCityBuild(101, "finished", "SUCCESS", "http://teamcity/101"));

        ReleaseResult result = releaseService.createRelease("180.0.0");

        assertThat(result.status()).isEqualTo(ReleaseStatus.SUCCESS);
        assertThat(result.repositories().get(0).status()).isEqualTo(RepositoryReleaseStatus.BUILD_SUCCESS);
        assertThat(result.repositories().get(0).buildRetried()).isTrue();
        assertThat(result.repositories().get(0).buildIds()).containsExactly(100L, 101L);
        verify(teamCityClient).getBuild(100);
        verify(teamCityClient).getBuild(101);
    }
}
