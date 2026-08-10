package com.dtim.releasecreator.service;

import com.dtim.releasecreator.dto.ReleasePreflightCheckResult;
import com.dtim.releasecreator.exception.ReleaseReportException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReleasePreflightCsvReportWriter {

    private static final String HEADER = "operationId;releaseVersion;previousReleaseVersion;repository;"
            + "check;sourceBranch;targetBranch;status;message";
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path reportDirectory;
    private final Clock clock;

    @Autowired
    public ReleasePreflightCsvReportWriter() {
        this(Path.of("reports", "releases"), Clock.systemDefaultZone());
    }

    ReleasePreflightCsvReportWriter(Path reportDirectory, Clock clock) {
        this.reportDirectory = reportDirectory;
        this.clock = clock;
    }

    public Path write(
            String operationId,
            String releaseVersion,
            String previousReleaseVersion,
            Instant startedAt,
            List<ReleasePreflightCheckResult> results) {
        Path temporaryFile = null;
        try {
            Files.createDirectories(reportDirectory);
            String timestamp = startedAt.atZone(ZoneId.of(clock.getZone().getId())).format(FILE_TIMESTAMP);
            Path report = reportDirectory.resolve("release-preflight-" + releaseVersion + "-"
                    + timestamp + "-" + operationId + ".csv");
            temporaryFile = Files.createTempFile(reportDirectory, report.getFileName().toString() + ".", ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.newLine();
                for (ReleasePreflightCheckResult result : results) {
                    writer.write(toRow(operationId, releaseVersion, previousReleaseVersion, result));
                    writer.newLine();
                }
            }
            moveAtomically(temporaryFile, report);
            temporaryFile = null;
            return report;
        } catch (IOException exception) {
            throw new ReleaseReportException("Failed to write release preflight CSV report", exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The original report-writing failure is more important than cleanup failure.
                }
            }
        }
    }

    private String toRow(
            String operationId,
            String releaseVersion,
            String previousReleaseVersion,
            ReleasePreflightCheckResult result) {
        return String.join(";",
                csv(operationId),
                csv(releaseVersion),
                csv(previousReleaseVersion),
                csv(result.repository()),
                csv(result.check().name()),
                csv(result.sourceBranch()),
                csv(result.targetBranch()),
                csv(result.status().name()),
                csv(result.message()));
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuotes = value.contains(";") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needQuotes ? "\"" + escaped + "\"" : escaped;
    }
}
