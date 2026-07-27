package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.client.BitbucketPullRequest;
import com.dtim.releasecreator.dto.FinalizationStep;
import com.dtim.releasecreator.dto.ReleaseFinalizationResult;
import com.dtim.releasecreator.dto.ReleaseStatus;
import com.dtim.releasecreator.dto.RepositoryFinalizationStatus;
import com.dtim.releasecreator.exception.IntegrationException;
import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReleaseFinalizationServiceTest {

    @Mock
    private ReleaseValidator validator;
    @Mock
    private ReleaseRepositoryProvider repositoryProvider;
    @Mock
    private BitbucketClient bitbucketClient;
    @Mock
    private ReleaseFinalizationCsvReportWriter reportWriter;

    private ReleaseFinalizationService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseFinalizationService(validator, repositoryProvider, bitbucketClient, reportWriter);
        org.mockito.Mockito.lenient().when(reportWriter.writeReport(any()))
                .thenReturn(Path.of("reports", "release-finalizing", "result.csv"));
    }

    @Test
    void mergesReleasePrCreatesAndMergesDevelopPr() {
        BitbucketPullRequest releaseOpen = pullRequest(10, "OPEN", "release/181.0.0", "master");
        BitbucketPullRequest releaseMerged = pullRequest(10, "MERGED", "release/181.0.0", "master");
        BitbucketPullRequest developOpen = pullRequest(20, "OPEN", "master", "develop");
        BitbucketPullRequest developMerged = pullRequest(20, "MERGED", "master", "develop");
        when(repositoryProvider.getRepositoriesForRelease()).thenReturn(List.of("orders"));
        when(bitbucketClient.findPullRequests("orders", "release/181.0.0", "master"))
                .thenReturn(List.of(releaseOpen));
        when(bitbucketClient.mergePullRequest("orders", releaseOpen)).thenReturn(releaseMerged);
        when(bitbucketClient.findPullRequests("orders", "master", "develop")).thenReturn(List.of());
        when(bitbucketClient.hasChanges("orders", "develop", "master")).thenReturn(true);
        when(bitbucketClient.createPullRequest(any(), any(), any(), any(), any())).thenReturn(developOpen);
        when(bitbucketClient.mergePullRequest("orders", developOpen)).thenReturn(developMerged);

        ReleaseFinalizationResult result = service.finalizeRelease("181.0.0");

        assertThat(result.status()).isEqualTo(ReleaseStatus.SUCCESS);
        assertThat(result.successfulCount()).isEqualTo(1);
        assertThat(result.csvReportPath()).isEqualTo("reports/release-finalizing/result.csv");
        assertThat(result.repositories().get(0).status()).isEqualTo(RepositoryFinalizationStatus.SUCCESS);
        assertThat(result.repositories().get(0).releasePullRequestMerged()).isTrue();
        assertThat(result.repositories().get(0).developPullRequestCreated()).isTrue();
        assertThat(result.repositories().get(0).developPullRequestMerged()).isTrue();
    }

    @Test
    void repeatedRunRecognizesAlreadyFinalizedRepository() {
        BitbucketPullRequest releaseMerged = pullRequest(10, "MERGED", "release/181.0.0", "master");
        when(repositoryProvider.getRepositoriesForRelease()).thenReturn(List.of("orders"));
        when(bitbucketClient.findPullRequests("orders", "release/181.0.0", "master"))
                .thenReturn(List.of(releaseMerged));
        when(bitbucketClient.findPullRequests("orders", "master", "develop")).thenReturn(List.of());
        when(bitbucketClient.hasChanges("orders", "develop", "master")).thenReturn(false);

        ReleaseFinalizationResult result = service.finalizeRelease("181.0.0");

        assertThat(result.repositories().get(0).status())
                .isEqualTo(RepositoryFinalizationStatus.SUCCESS_ALREADY_FINALIZED);
        verify(bitbucketClient, never()).createPullRequest(any(), any(), any(), any(), any());
        verify(bitbucketClient, never()).mergePullRequest(any(), any());
    }

    @Test
    void skipsMissingReleasePrAndContinuesAfterAnotherRepositoryFails() {
        BitbucketPullRequest releaseOpen = pullRequest(10, "OPEN", "release/181.0.0", "master");
        when(repositoryProvider.getRepositoriesForRelease()).thenReturn(List.of("missing", "broken"));
        when(bitbucketClient.findPullRequests("missing", "release/181.0.0", "master"))
                .thenReturn(List.of());
        when(bitbucketClient.findPullRequests("broken", "release/181.0.0", "master"))
                .thenReturn(List.of(releaseOpen));
        when(bitbucketClient.mergePullRequest("broken", releaseOpen))
                .thenThrow(new IntegrationException("merge vetoed"));

        ReleaseFinalizationResult result = service.finalizeRelease("181.0.0");

        assertThat(result.status()).isEqualTo(ReleaseStatus.FAILURE);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.repositories()).extracting(item -> item.repoSlug() + ":" + item.status())
                .containsExactly(
                        "missing:SKIPPED_RELEASE_PR_NOT_FOUND",
                        "broken:FAILED_RELEASE_PR_MERGE");
        assertThat(result.repositories().get(1).errorStep()).isEqualTo(FinalizationStep.MERGE_RELEASE_PR);
    }

    @Test
    void rejectsInvalidVersionBeforeCallingIntegrations() {
        org.mockito.Mockito.doThrow(new InvalidReleaseNumberException("bad"))
                .when(validator).validateVersion("bad");

        assertThatThrownBy(() -> service.finalizeRelease("bad"))
                .isInstanceOf(InvalidReleaseNumberException.class);
        verifyNoInteractions(repositoryProvider, bitbucketClient);
    }

    private BitbucketPullRequest pullRequest(long id, String state, String from, String to) {
        return new BitbucketPullRequest(
                id,
                1,
                state,
                "refs/heads/" + from,
                "refs/heads/" + to,
                "http://bitbucket/pr/" + id);
    }
}
