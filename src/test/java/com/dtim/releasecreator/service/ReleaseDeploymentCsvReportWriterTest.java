package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dtim.releasecreator.dto.DeploymentStatus;
import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.dto.ServiceDeploymentResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseDeploymentCsvReportWriterTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void writesUtf8SemicolonSeparatedReportAndEscapesValues() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T11:25:30Z"), ZoneOffset.UTC);
        ReleaseDeploymentCsvReportWriter writer =
                new ReleaseDeploymentCsvReportWriter(tempDirectory, clock);
        ServiceDeploymentResult service = new ServiceDeploymentResult(
                "calc;service",
                DeploymentStatus.FAILED,
                null,
                null,
                null,
                null,
                null,
                "Failure \"quoted\"\nnext line");
        ReleaseDeploymentResult result = new ReleaseDeploymentResult(
                "180.0.0", "UAT", 1, 0, 1, null, List.of(service));

        Path report = writer.writeUatDeploymentReport(result);

        assertThat(report.getFileName().toString())
                .isEqualTo("uat-deployment-180.0.0-20260713-112530.csv");
        assertThat(Files.readString(report, StandardCharsets.UTF_8))
                .startsWith("releaseVersion;environment;repoSlug;status;sourceBuildId;")
                .contains("180.0.0;UAT;\"calc;service\";FAILED;;;;;;")
                .contains("\"Failure \"\"quoted\"\"\nnext line\"");
    }
}
