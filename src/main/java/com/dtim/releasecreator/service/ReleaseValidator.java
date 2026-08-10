package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.config.BitbucketProperties;
import com.dtim.releasecreator.dto.ReleasePreflightCheck;
import com.dtim.releasecreator.dto.ReleasePreflightCheckResult;
import com.dtim.releasecreator.dto.ReleasePreflightCheckStatus;
import com.dtim.releasecreator.exception.InvalidReleaseNumberException;
import com.dtim.releasecreator.exception.ReleasePreflightException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.dtim.releasecreator.exception.InvalidReleaseTaskNumberException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReleaseValidator {
    private static final Logger log = LoggerFactory.getLogger(ReleaseValidator.class);
    private static final String RELEASE_BRANCH_PREFIX = "release/";

    private final Pattern releaseNumberPattern;
    private final Pattern releaseTaskNumberPattern;
    private final BitbucketClient bitbucketClient;
    private final BitbucketProperties bitbucketProperties;
    private final ReleasePreflightCsvReportWriter reportWriter;

    public ReleaseValidator(
            @Value("${validation.task-number-pattern}") String taskNumberPattern,
            BitbucketClient bitbucketClient,
            BitbucketProperties bitbucketProperties,
            ReleasePreflightCsvReportWriter reportWriter) {
        this.releaseNumberPattern  = Pattern.compile("^\\d{3}\\.\\d+\\.\\d+$");
        this.releaseTaskNumberPattern  = Pattern.compile(taskNumberPattern);
        this.bitbucketClient = bitbucketClient;
        this.bitbucketProperties = bitbucketProperties;
        this.reportWriter = reportWriter;
    }

    public void validateVersion(String releaseVersion) {
        if (!releaseNumberPattern.matcher(releaseVersion).matches()) {
            throw new InvalidReleaseNumberException(releaseVersion);
        }
    }

    public void validateTaskNumber(String releaseTaskNumber) {
        if (!releaseTaskNumberPattern.matcher(releaseTaskNumber).matches()) {
            throw new InvalidReleaseTaskNumberException(releaseTaskNumber);
        }
    }

    public void validateRepositoryPreflight(
            String releaseVersion,
            List<String> repositories,
            String operationId,
            Instant startedAt) {
        String previousReleaseVersion = previousReleaseVersion(releaseVersion);
        String previousReleaseBranch = RELEASE_BRANCH_PREFIX + previousReleaseVersion;
        List<ReleasePreflightCheckResult> results = new ArrayList<>();

        log.info("PREFLIGHT COMMIT VALIDATION STARTED | previousReleaseBranch={} repositories={}",
                previousReleaseBranch, repositories.size());
        for (String repository : repositories) {
            try (MDC.MDCCloseable ignored = MDC.putCloseable("repository", repository)) {
                validateRepository(repository, previousReleaseBranch, results);
            }
        }

        List<ReleasePreflightCheckResult> failures = results.stream()
                .filter(ReleasePreflightCheckResult::blocksRelease)
                .toList();
        if (failures.isEmpty()) {
            log.info("PREFLIGHT COMMIT VALIDATION SUCCEEDED | repositories={} checks={}",
                    repositories.size(), results.size());
            return;
        }

        Path report = reportWriter.write(
                operationId, releaseVersion, previousReleaseVersion, startedAt, results);
        String reportPath = report.toString().replace('\\', '/');
        log.error("PREFLIGHT COMMIT VALIDATION FAILED | failures={} repositories={} csvReport={}",
                failures.size(), failures.stream().map(ReleasePreflightCheckResult::repository).distinct().count(),
                reportPath);
        throw new ReleasePreflightException(reportPath, failures);
    }

    private void validateRepository(
            String repository,
            String previousReleaseBranch,
            List<ReleasePreflightCheckResult> results) {
        Boolean previousBranchExists = branchExists(repository, previousReleaseBranch, results);
        if (Boolean.TRUE.equals(previousBranchExists)) {
            compare(repository, previousReleaseBranch, bitbucketProperties.masterBranch(),
                    ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_MASTER, results);
            compare(repository, previousReleaseBranch, bitbucketProperties.developBranch(),
                    ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_DEVELOP, results);
        } else if (Boolean.FALSE.equals(previousBranchExists)) {
            skipPreviousReleaseChecks(repository, previousReleaseBranch, results);
        }

        compare(repository, bitbucketProperties.masterBranch(), bitbucketProperties.developBranch(),
                ReleasePreflightCheck.MASTER_MERGED_TO_DEVELOP, results);
    }

    private Boolean branchExists(
            String repository,
            String previousReleaseBranch,
            List<ReleasePreflightCheckResult> results) {
        try {
            boolean exists = bitbucketClient.branchExists(repository, previousReleaseBranch);
            log.info("PREFLIGHT PREVIOUS RELEASE BRANCH CHECKED | branch={} exists={}",
                    previousReleaseBranch, exists);
            return exists;
        } catch (RuntimeException exception) {
            String message = "Failed to check previous release branch: " + safeMessage(exception);
            results.add(result(repository, ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_MASTER,
                    previousReleaseBranch, bitbucketProperties.masterBranch(),
                    ReleasePreflightCheckStatus.ERROR, message));
            results.add(result(repository, ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_DEVELOP,
                    previousReleaseBranch, bitbucketProperties.developBranch(),
                    ReleasePreflightCheckStatus.ERROR, message));
            log.error("PREFLIGHT PREVIOUS RELEASE BRANCH CHECK FAILED | branch={} error={}",
                    previousReleaseBranch, message, exception);
            return null;
        }
    }

    private void skipPreviousReleaseChecks(
            String repository,
            String previousReleaseBranch,
            List<ReleasePreflightCheckResult> results) {
        String message = "Previous release branch does not exist";
        results.add(result(repository, ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_MASTER,
                previousReleaseBranch, bitbucketProperties.masterBranch(),
                ReleasePreflightCheckStatus.SKIPPED, message));
        results.add(result(repository, ReleasePreflightCheck.PREVIOUS_RELEASE_MERGED_TO_DEVELOP,
                previousReleaseBranch, bitbucketProperties.developBranch(),
                ReleasePreflightCheckStatus.SKIPPED, message));
    }

    private void compare(
            String repository,
            String sourceBranch,
            String targetBranch,
            ReleasePreflightCheck check,
            List<ReleasePreflightCheckResult> results) {
        try {
            boolean hasUnmergedCommits = bitbucketClient.haveCommitsDiffer(sourceBranch, targetBranch, repository);
            ReleasePreflightCheckStatus status = hasUnmergedCommits
                    ? ReleasePreflightCheckStatus.FAILED
                    : ReleasePreflightCheckStatus.PASSED;
            String message = hasUnmergedCommits
                    ? "Source branch contains commits that are not merged into target branch"
                    : "All source branch commits are merged into target branch";
            results.add(result(repository, check, sourceBranch, targetBranch, status, message));
            if (hasUnmergedCommits) {
                log.error("PREFLIGHT COMMIT CHECK FAILED | check={} sourceBranch={} targetBranch={}",
                        check, sourceBranch, targetBranch);
            } else {
                log.info("PREFLIGHT COMMIT CHECK PASSED | check={} sourceBranch={} targetBranch={}",
                        check, sourceBranch, targetBranch);
            }
        } catch (RuntimeException exception) {
            String message = "Failed to compare commits: " + safeMessage(exception);
            results.add(result(repository, check, sourceBranch, targetBranch,
                    ReleasePreflightCheckStatus.ERROR, message));
            log.error("PREFLIGHT COMMIT CHECK ERROR | check={} sourceBranch={} targetBranch={} error={}",
                    check, sourceBranch, targetBranch, message, exception);
        }
    }

    private ReleasePreflightCheckResult result(
            String repository,
            ReleasePreflightCheck check,
            String sourceBranch,
            String targetBranch,
            ReleasePreflightCheckStatus status,
            String message) {
        return new ReleasePreflightCheckResult(repository, check, sourceBranch, targetBranch, status, message);
    }

    private String previousReleaseVersion(String releaseVersion) {
        String[] parts = releaseVersion.split("\\.");
        int major = Integer.parseInt(parts[0]);
        if (major == 0) {
            throw new InvalidReleaseNumberException(releaseVersion);
        }
        return String.format("%03d.%s.%s", major - 1, parts[1], parts[2]);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
