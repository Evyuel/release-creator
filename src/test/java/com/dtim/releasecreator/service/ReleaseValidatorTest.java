package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.config.BitbucketProperties;
import com.dtim.releasecreator.dto.ReleasePreflightCheck;
import com.dtim.releasecreator.dto.ReleasePreflightCheckResult;
import com.dtim.releasecreator.dto.ReleasePreflightCheckStatus;
import com.dtim.releasecreator.exception.ReleasePreflightException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReleaseValidatorTest {

    @Mock
    private BitbucketClient bitbucketClient;

    @Mock
    private ReleasePreflightCsvReportWriter reportWriter;

    private ReleaseValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReleaseValidator(
                "^TTTPLN-\\d+$",
                bitbucketClient,
                new BitbucketProperties(
                        URI.create("http://bitbucket"), "", "", "MYPROJ", "develop", "master"),
                reportWriter);
    }

    @Test
    void skipsPreviousReleaseChecksWhenBranchDoesNotExistAndChecksMasterAgainstDevelop() {
        validator.validateRepositoryPreflight(
                "120.0.0", List.of("orders"), "operation", Instant.parse("2026-08-10T10:00:00Z"));

        verify(bitbucketClient).branchExists("orders", "release/119.0.0");
        verify(bitbucketClient).haveCommitsDiffer("master", "develop", "orders");
    }

    @Test
    void collectsAllRepositoryFailuresBeforeWritingReportAndAborting() {
        when(bitbucketClient.branchExists("orders", "release/119.0.0")).thenReturn(true);
        when(bitbucketClient.haveCommitsDiffer("release/119.0.0", "master", "orders")).thenReturn(true);
        when(bitbucketClient.haveCommitsDiffer("release/119.0.0", "develop", "orders")).thenReturn(false);
        when(bitbucketClient.haveCommitsDiffer("master", "develop", "orders")).thenReturn(true);
        when(reportWriter.write(eq("operation"), eq("120.0.0"), eq("119.0.0"), any(), any()))
                .thenReturn(Path.of("reports", "releases", "preflight.csv"));

        assertThatThrownBy(() -> validator.validateRepositoryPreflight(
                "120.0.0", List.of("orders", "billing"), "operation", Instant.parse("2026-08-10T10:00:00Z")))
                .isInstanceOf(ReleasePreflightException.class)
                .satisfies(exception -> {
                    ReleasePreflightException preflight = (ReleasePreflightException) exception;
                    assertThat(preflight.getCsvReportPath()).isEqualTo("reports/releases/preflight.csv");
                    assertThat(preflight.getFailures())
                            .extracting(ReleasePreflightCheckResult::check)
                            .containsExactly(
                                    ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_MASTER,
                                    ReleasePreflightCheck.MASTER_MERGED_TO_DEVELOP);
                });

        verify(bitbucketClient).branchExists("billing", "release/119.0.0");
        verify(bitbucketClient).haveCommitsDiffer("master", "develop", "billing");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReleasePreflightCheckResult>> resultsCaptor = ArgumentCaptor.forClass(List.class);
        verify(reportWriter).write(eq("operation"), eq("120.0.0"), eq("119.0.0"), any(), resultsCaptor.capture());
        assertThat(resultsCaptor.getValue()).hasSize(6);
        assertThat(resultsCaptor.getValue())
                .filteredOn(result -> result.status() == ReleasePreflightCheckStatus.SKIPPED)
                .hasSize(2);
    }

    @Test
    void recordsBranchLookupErrorsAndContinuesWithMasterCheck() {
        when(bitbucketClient.branchExists("orders", "release/119.0.0"))
                .thenThrow(new IllegalStateException("Bitbucket unavailable"));
        when(reportWriter.write(any(), any(), any(), any(), any()))
                .thenReturn(Path.of("reports", "releases", "preflight.csv"));

        assertThatThrownBy(() -> validator.validateRepositoryPreflight(
                "120.0.0", List.of("orders"), "operation", Instant.parse("2026-08-10T10:00:00Z")))
                .isInstanceOf(ReleasePreflightException.class)
                .satisfies(exception -> assertThat(((ReleasePreflightException) exception).getFailures())
                        .hasSize(2)
                        .allMatch(result -> result.status() == ReleasePreflightCheckStatus.ERROR));

        verify(bitbucketClient).haveCommitsDiffer("master", "develop", "orders");
    }
}
