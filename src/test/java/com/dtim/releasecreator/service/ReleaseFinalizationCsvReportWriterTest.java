package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dtim.releasecreator.dto.FinalizationStep;
import com.dtim.releasecreator.dto.ReleaseFinalizationResult;
import com.dtim.releasecreator.dto.ReleaseStatus;
import com.dtim.releasecreator.dto.RepositoryFinalizationResult;
import com.dtim.releasecreator.dto.RepositoryFinalizationStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseFinalizationCsvReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesDetailedEscapedReportUsingUniqueOperationId() throws Exception {
        Instant startedAt = Instant.parse("2026-07-15T10:20:30Z");
        RepositoryFinalizationResult repository = new RepositoryFinalizationResult(
                "orders",
                RepositoryFinalizationStatus.FAILED_DEVELOP_PR_MERGE,
                "release/181.0.0",
                10L,
                "http://bitbucket/pr/10",
                "MERGED",
                true,
                20L,
                "http://bitbucket/pr/20",
                "OPEN",
                true,
                false,
                FinalizationStep.MERGE_DEVELOP_PR,
                "conflict; approval required");
        ReleaseFinalizationResult result = new ReleaseFinalizationResult(
                "f82ab3c1", "181.0.0", ReleaseStatus.FAILURE,
                startedAt, startedAt.plusSeconds(5), 5000,
                1, 0, 0, 1, null, List.of(repository));
        ReleaseFinalizationCsvReportWriter writer = new ReleaseFinalizationCsvReportWriter(
                tempDir, Clock.fixed(startedAt, ZoneOffset.UTC));

        Path report = writer.writeReport(result);
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);

        assertThat(report.getFileName().toString())
                .isEqualTo("release-finalizing-181.0.0-20260715-102030-f82ab3c1.csv");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("operationId;releaseNumber;finalizationStatus");
        assertThat(lines.get(1)).contains(";orders;FAILED_DEVELOP_PR_MERGE;release/181.0.0;")
                .endsWith(";MERGE_DEVELOP_PR;\"conflict; approval required\"");
    }
}
