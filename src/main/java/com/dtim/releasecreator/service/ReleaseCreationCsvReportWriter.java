package com.dtim.releasecreator.service;

import com.dtim.releasecreator.dto.ReleaseResult;
import com.dtim.releasecreator.dto.RepositoryReleaseResult;
import com.dtim.releasecreator.dto.RepositoryReleaseStatus;
import com.dtim.releasecreator.exception.ReleaseReportException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReleaseCreationCsvReportWriter {

    private static final String HEADER = "operationId;releaseVersion;releaseStatus;startedAt;finishedAt;durationMs;"
            + "repoSlug;status;skipReason;releaseBranch;pullRequestId;pullRequestUrl;"
            + "initialBuildId;retryBuildId;buildRetried;errorMessage";
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path reportDirectory;
    private final Clock clock;

    @Autowired
    public ReleaseCreationCsvReportWriter() {
        this(Path.of("reports", "releases"), Clock.systemDefaultZone());
    }

    ReleaseCreationCsvReportWriter(Path reportDirectory, Clock clock) {
        this.reportDirectory = reportDirectory;
        this.clock = clock;
    }

    public Path writeReleaseCreationReport(ReleaseResult result) {
        Path temporaryFile = null;
        try {
            Files.createDirectories(reportDirectory);
            String timestamp = result.startedAt()
                    .atZone(ZoneId.of(clock.getZone().getId()))
                    .format(FILE_TIMESTAMP);
            Path report = reportDirectory.resolve("release-creation-"
                    + result.releaseNumber() + "-" + timestamp + "-" + result.operationId() + ".csv");
            temporaryFile = Files.createTempFile(reportDirectory, report.getFileName().toString() + ".", ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.newLine();
                for (RepositoryReleaseResult repository : result.repositories()) {
                    writer.write(toCsvRow(result, repository));
                    writer.newLine();
                }
            }
            moveAtomically(temporaryFile, report);
            temporaryFile = null;
            return report;
        } catch (IOException exception) {
            throw new ReleaseReportException("Failed to write release creation CSV report", exception);
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

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String toCsvRow(ReleaseResult result, RepositoryReleaseResult repository) {
        return String.join(";",
                csv(result.operationId()),
                csv(result.releaseNumber()),
                csv(result.status().name()),
                csv(result.startedAt().toString()),
                csv(result.finishedAt().toString()),
                csv(result.durationMillis()),
                csv(repository.repository()),
                csv(csvStatus(repository.status())),
                csv(skipReason(repository.status())),
                csv(repository.branchName()),
                csv(repository.pullRequestId()),
                csv(repository.pullRequestUrl()),
                csv(buildId(repository, 0)),
                csv(buildId(repository, 1)),
                csv(Boolean.toString(repository.buildRetried())),
                csv(repository.error()));
    }

    private String csvStatus(RepositoryReleaseStatus status) {
        return status == RepositoryReleaseStatus.SKIPPED_NO_CHANGES ? "SKIPPED" : status.name();
    }

    private String skipReason(RepositoryReleaseStatus status) {
        return status == RepositoryReleaseStatus.SKIPPED_NO_CHANGES ? "NO_CHANGES" : null;
    }

    private Long buildId(RepositoryReleaseResult repository, int index) {
        return repository.buildIds().size() > index ? repository.buildIds().get(index) : null;
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

    private String csv(long value) {
        return Long.toString(value);
    }
}
