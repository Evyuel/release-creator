package com.dtim.releasecreator.service;

import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.dto.ServiceDeploymentResult;
import com.dtim.releasecreator.exception.DeploymentReportException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReleaseDeploymentCsvReportWriter {

    private static final String HEADER = "releaseVersion;environment;repoSlug;status;sourceBuildId;"
            + "sourceBuildNumber;sourceBuildUrl;teamCityDeployBuildId;teamCityDeployBuildUrl;errorMessage";
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path reportDirectory;
    private final Clock clock;

    @Autowired
    public ReleaseDeploymentCsvReportWriter() {
        this(Path.of("reports", "deployments"), Clock.systemDefaultZone());
    }

    ReleaseDeploymentCsvReportWriter(Path reportDirectory, Clock clock) {
        this.reportDirectory = reportDirectory;
        this.clock = clock;
    }

    public Path writeUatDeploymentReport(ReleaseDeploymentResult result) {
        try {
            Files.createDirectories(reportDirectory);
            String timestamp = LocalDateTime.now(clock).format(FILE_TIMESTAMP);
            Path report = reportDirectory.resolve(
                    "uat-deployment-" + result.releaseVersion() + "-" + timestamp + ".csv");
            try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.newLine();
                for (ServiceDeploymentResult service : result.services()) {
                    writer.write(toCsvRow(result, service));
                    writer.newLine();
                }
            }
            return report;
        } catch (IOException exception) {
            throw new DeploymentReportException("Failed to write UAT deployment CSV report", exception);
        }
    }

    private String toCsvRow(ReleaseDeploymentResult result, ServiceDeploymentResult service) {
        return String.join(";",
                csv(result.releaseVersion()),
                csv(result.environment()),
                csv(service.repoSlug()),
                csv(service.status().name()),
                csv(service.sourceBuildId()),
                csv(service.sourceBuildNumber()),
                csv(service.sourceBuildUrl()),
                csv(service.teamCityDeployBuildId()),
                csv(service.teamCityDeployBuildUrl()),
                csv(service.errorMessage()));
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuotes = value.contains(";")
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private String csv(Long value) {
        return value == null ? "" : value.toString();
    }
}
