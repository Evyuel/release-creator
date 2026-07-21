package com.dtim.releasecreator.service;

import com.dtim.releasecreator.dto.ReleaseFinalizationResult;
import com.dtim.releasecreator.dto.RepositoryFinalizationResult;
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
public class ReleaseFinalizationCsvReportWriter {

    private static final String HEADER = "operationId;releaseNumber;finalizationStatus;startedAt;finishedAt;durationMs;"
            + "repoSlug;repositoryStatus;releaseBranch;releasePullRequestId;releasePullRequestUrl;"
            + "releasePullRequestStatus;releasePullRequestMerged;developPullRequestId;developPullRequestUrl;"
            + "developPullRequestStatus;developPullRequestCreated;developPullRequestMerged;errorStep;errorMessage";
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path reportDirectory;
    private final Clock clock;

    @Autowired
    public ReleaseFinalizationCsvReportWriter() {
        this(Path.of("reports", "release-finalizing"), Clock.systemDefaultZone());
    }

    ReleaseFinalizationCsvReportWriter(Path reportDirectory, Clock clock) {
        this.reportDirectory = reportDirectory;
        this.clock = clock;
    }

    public Path writeReport(ReleaseFinalizationResult result) {
        Path temporaryFile = null;
        try {
            Files.createDirectories(reportDirectory);
            String timestamp = result.startedAt()
                    .atZone(ZoneId.of(clock.getZone().getId()))
                    .format(FILE_TIMESTAMP);
            Path report = reportDirectory.resolve("release-finalizing-"
                    + result.releaseNumber() + "-" + timestamp + "-" + result.operationId() + ".csv");
            temporaryFile = Files.createTempFile(reportDirectory, report.getFileName().toString() + ".", ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.newLine();
                for (RepositoryFinalizationResult repository : result.repositories()) {
                    writer.write(toCsvRow(result, repository));
                    writer.newLine();
                }
            }
            moveAtomically(temporaryFile, report);
            temporaryFile = null;
            return report;
        } catch (IOException exception) {
            throw new ReleaseReportException("Failed to write release finalization CSV report", exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // Keep the original report-writing exception.
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

    private String toCsvRow(ReleaseFinalizationResult result, RepositoryFinalizationResult repository) {
        return String.join(";",
                csv(result.operationId()),
                csv(result.releaseNumber()),
                csv(result.status().name()),
                csv(result.startedAt().toString()),
                csv(result.finishedAt().toString()),
                csv(result.durationMillis()),
                csv(repository.repoSlug()),
                csv(repository.status().name()),
                csv(repository.releaseBranch()),
                csv(repository.releasePullRequestId()),
                csv(repository.releasePullRequestUrl()),
                csv(repository.releasePullRequestStatus()),
                csv(repository.releasePullRequestMerged()),
                csv(repository.developPullRequestId()),
                csv(repository.developPullRequestUrl()),
                csv(repository.developPullRequestStatus()),
                csv(repository.developPullRequestCreated()),
                csv(repository.developPullRequestMerged()),
                csv(repository.errorStep() == null ? null : repository.errorStep().name()),
                csv(repository.errorMessage()));
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

    private String csv(Long value) {
        return value == null ? "" : value.toString();
    }

    private String csv(long value) {
        return Long.toString(value);
    }

    private String csv(boolean value) {
        return Boolean.toString(value);
    }
}
