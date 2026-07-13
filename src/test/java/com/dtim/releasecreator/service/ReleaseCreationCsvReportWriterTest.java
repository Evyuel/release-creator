package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dtim.releasecreator.dto.ReleaseResult;
import com.dtim.releasecreator.dto.ReleaseStatus;
import com.dtim.releasecreator.dto.RepositoryReleaseResult;
import com.dtim.releasecreator.dto.RepositoryReleaseStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseCreationCsvReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesOneEscapedRowPerRepositoryWithBuildAttempts() throws Exception {
        Instant startedAt = Instant.parse("2026-07-13T11:30:15Z");
        ReleaseResult result = new ReleaseResult(
                "a8f31c42",
                "180.0.0",
                ReleaseStatus.PARTIAL_FAILURE,
                startedAt,
                startedAt.plusSeconds(10),
                10_000,
                null,
                List.of(
                        new RepositoryReleaseResult(
                                "orders", RepositoryReleaseStatus.BUILD_SUCCESS, "release/180.0.0",
                                42L, "http://bitbucket/pr/42", List.of(100L, 101L), true, null),
                        new RepositoryReleaseResult(
                                "billing", RepositoryReleaseStatus.SKIPPED_NO_CHANGES, "release/180.0.0",
                                null, null, List.of(), false, "not;used")));
        ReleaseCreationCsvReportWriter writer = new ReleaseCreationCsvReportWriter(
                tempDir, Clock.fixed(startedAt, ZoneOffset.UTC));

        Path report = writer.writeReleaseCreationReport(result);
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);

        assertThat(report.getFileName().toString())
                .isEqualTo("release-creation-180.0.0-20260713-113015-a8f31c42.csv");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("operationId;releaseVersion;releaseStatus;startedAt;finishedAt;durationMs;"
                + "repoSlug;status;skipReason;releaseBranch;pullRequestId;pullRequestUrl;"
                + "initialBuildId;retryBuildId;buildRetried;errorMessage");
        assertThat(lines.get(1)).contains(";orders;BUILD_SUCCESS;;release/180.0.0;42;http://bitbucket/pr/42;100;101;true;");
        assertThat(lines.get(2)).contains(";billing;SKIPPED;NO_CHANGES;release/180.0.0;;;;;false;\"not;used\"");
    }
}
