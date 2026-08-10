package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dtim.releasecreator.dto.ReleasePreflightCheck;
import com.dtim.releasecreator.dto.ReleasePreflightCheckResult;
import com.dtim.releasecreator.dto.ReleasePreflightCheckStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleasePreflightCsvReportWriterTest {

    @TempDir
    Path reportDirectory;

    @Test
    void writesEveryCheckResultToUtf8Csv() throws Exception {
        Instant startedAt = Instant.parse("2026-08-10T10:15:30Z");
        ReleasePreflightCsvReportWriter writer = new ReleasePreflightCsvReportWriter(
                reportDirectory, Clock.fixed(startedAt, ZoneOffset.UTC));
        List<ReleasePreflightCheckResult> results = List.of(
                new ReleasePreflightCheckResult(
                        "orders",
                        ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_MASTER,
                        "release/119.0.0",
                        "master",
                        ReleasePreflightCheckStatus.FAILED,
                        "Unmerged commits; inspect history"),
                new ReleasePreflightCheckResult(
                        "billing",
                        ReleasePreflightCheck.MASTER_MERGED_TO_DEVELOP,
                        "master",
                        "develop",
                        ReleasePreflightCheckStatus.PASSED,
                        "All commits are merged"));

        Path report = writer.write("abcd1234", "120.0.0", "119.0.0", startedAt, results);

        assertThat(report.getFileName().toString())
                .isEqualTo("release-preflight-120.0.0-20260810-101530-abcd1234.csv");
        assertThat(Files.readAllLines(report, StandardCharsets.UTF_8))
                .containsExactly(
                        "operationId;releaseVersion;previousReleaseVersion;repository;check;sourceBranch;targetBranch;status;message",
                        "abcd1234;120.0.0;119.0.0;orders;PREVIOUS_RELEASE_MERGED_TO_MASTER;release/119.0.0;master;FAILED;\"Unmerged commits; inspect history\"",
                        "abcd1234;120.0.0;119.0.0;billing;MASTER_MERGED_TO_DEVELOP;master;develop;PASSED;All commits are merged");
    }
}
