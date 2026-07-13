package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.client.PullRequestInfo;
import com.dtim.releasecreator.client.TeamCityBuild;
import com.dtim.releasecreator.client.TeamCityClient;
import com.dtim.releasecreator.config.TeamCityProperties;
import com.dtim.releasecreator.dto.ReleaseResult;
import com.dtim.releasecreator.dto.ReleaseStatus;
import com.dtim.releasecreator.dto.RepositoryReleaseResult;
import com.dtim.releasecreator.dto.RepositoryReleaseStatus;
import com.dtim.releasecreator.exception.ReleaseBranchConflictException;
import com.dtim.releasecreator.exception.ReleaseInProgressException;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);
    private static final String RELEASE_BRANCH_PREFIX = "release/";
    private static final String SEPARATOR = "================================================================================";

    private final BitbucketClient bitbucketClient;
    private final TeamCityClient teamCityClient;
    private final TeamCityProperties teamCityProperties;
    private final ReleaseVersionValidator releaseVersionValidator;
    private final ReleaseRepositoryProvider releaseRepositoryProvider;
    private final ReleaseCreationCsvReportWriter reportWriter;
    private final AtomicBoolean releaseInProgress = new AtomicBoolean(false);

    public ReleaseService(
            BitbucketClient bitbucketClient,
            TeamCityClient teamCityClient,
            TeamCityProperties teamCityProperties,
            ReleaseVersionValidator releaseVersionValidator,
            ReleaseRepositoryProvider releaseRepositoryProvider,
            ReleaseCreationCsvReportWriter reportWriter) {
        this.bitbucketClient = bitbucketClient;
        this.teamCityClient = teamCityClient;
        this.teamCityProperties = teamCityProperties;
        this.releaseVersionValidator = releaseVersionValidator;
        this.releaseRepositoryProvider = releaseRepositoryProvider;
        this.reportWriter = reportWriter;
    }

    public ReleaseResult createRelease(String releaseNumber) {
        if (!releaseInProgress.compareAndSet(false, true)) {
            throw new ReleaseInProgressException();
        }

        Instant startedAt = Instant.now();
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("releaseNumber", releaseNumber)) {
            log.info(SEPARATOR);
            log.info("RELEASE STARTED | releaseNumber={}", releaseNumber);
            releaseVersionValidator.validate(releaseNumber);
            log.info("RELEASE VALIDATED | releaseNumber={}", releaseNumber);

            List<String> repositories = releaseRepositoryProvider.getRepositoriesForRelease();
            String branchName = RELEASE_BRANCH_PREFIX + releaseNumber;
            ensureReleaseBranchDoesNotExist(repositories, branchName);

            List<RepositoryExecution> executions = new ArrayList<>();
            for (String repository : repositories) {
                executions.add(prepareRepository(repository, branchName, releaseNumber));
            }

            waitForBuilds(executions, branchName);
            Instant finishedAt = Instant.now();
            ReleaseResult result = toResult(operationId, releaseNumber, startedAt, finishedAt, executions);
            result = writeReport(result);
            logSummary(result);
            return result;
        } finally {
            releaseInProgress.set(false);
        }
    }

    private void ensureReleaseBranchDoesNotExist(List<String> repositories, String branchName) {
        log.info("PREFLIGHT STARTED | checking branch={} repositories={}", branchName, repositories.size());
        List<String> conflicts = new ArrayList<>();
        for (String repository : repositories) {
            if (bitbucketClient.branchExists(repository, branchName)) {
                conflicts.add(repository);
            }
        }
        if (!conflicts.isEmpty()) {
            log.error("PREFLIGHT FAILED | branch={} existingRepositories={}", branchName, conflicts);
            throw new ReleaseBranchConflictException(branchName, conflicts);
        }
        log.info("PREFLIGHT SUCCEEDED | branch={} does not exist", branchName);
    }

    private RepositoryExecution prepareRepository(
            String repository,
            String branchName,
            String releaseNumber) {
        RepositoryExecution execution = new RepositoryExecution(repository, branchName);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("repository", repository)) {
            log.info(SEPARATOR);
            log.info("REPOSITORY STARTED");
            if (!bitbucketClient.hasChangesBetweenMasterAndDevelop(repository)) {
                execution.status = RepositoryReleaseStatus.SKIPPED_NO_CHANGES;
                log.info("REPOSITORY SKIPPED | reason=NO_CHANGES master..develop");
                return execution;
            }

            log.info("CHANGES FOUND | comparing master..develop");
            bitbucketClient.createBranch(repository, branchName);
            log.info("BRANCH CREATED | branch={}", branchName);

            PullRequestInfo pullRequest = bitbucketClient.createPullRequest(repository, branchName, releaseNumber);
            execution.pullRequestId = pullRequest.id();
            execution.pullRequestUrl = pullRequest.url();
            log.info("PULL REQUEST CREATED | pullRequestId={} url={}", pullRequest.id(), pullRequest.url());

            TeamCityBuild build = teamCityClient.triggerBuild(repository, branchName);
            execution.buildIds.add(build.id());
            execution.currentBuildId = build.id();
            execution.status = RepositoryReleaseStatus.BUILD_QUEUED;
            log.info("TEAMCITY BUILD QUEUED | buildId={} url={}", build.id(), build.webUrl());
        } catch (RuntimeException exception) {
            execution.status = RepositoryReleaseStatus.PREPARATION_FAILED;
            execution.error = safeMessage(exception);
            log.error("REPOSITORY PREPARATION FAILED | error={}", execution.error, exception);
        }
        return execution;
    }

    private void waitForBuilds(List<RepositoryExecution> executions, String branchName) {
        List<RepositoryExecution> active = executions.stream()
                .filter(execution -> execution.status == RepositoryReleaseStatus.BUILD_QUEUED)
                .toList();
        if (active.isEmpty()) {
            log.info("TEAMCITY WAIT SKIPPED | no builds were queued");
            return;
        }

        Instant deadline = Instant.now().plus(teamCityProperties.waitTimeout());
        log.info("TEAMCITY WAIT STARTED | builds={} timeout={}", active.size(), teamCityProperties.waitTimeout());
        while (active.stream().anyMatch(RepositoryExecution::waitingForBuild)) {
            if (Instant.now().isAfter(deadline)) {
                active.stream()
                        .filter(RepositoryExecution::waitingForBuild)
                        .forEach(execution -> {
                            execution.status = RepositoryReleaseStatus.BUILD_FAILED;
                            execution.error = "Timed out waiting for TeamCity build";
                            log.error("TEAMCITY BUILD TIMEOUT | repository={} buildId={}",
                                    execution.repository, execution.currentBuildId);
                        });
                break;
            }

            for (RepositoryExecution execution : active) {
                if (!execution.waitingForBuild()) {
                    continue;
                }
                try (MDC.MDCCloseable ignored = MDC.putCloseable("repository", execution.repository)) {
                    pollBuild(execution, branchName);
                } catch (RuntimeException exception) {
                    execution.status = RepositoryReleaseStatus.BUILD_FAILED;
                    execution.error = safeMessage(exception);
                    log.error("TEAMCITY POLLING FAILED | buildId={} error={}",
                            execution.currentBuildId, execution.error, exception);
                }
            }

            if (active.stream().anyMatch(RepositoryExecution::waitingForBuild)) {
                try {
                    Thread.sleep(Math.max(1L, teamCityProperties.pollInterval().toMillis()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    active.stream()
                            .filter(RepositoryExecution::waitingForBuild)
                            .forEach(execution -> {
                                execution.status = RepositoryReleaseStatus.BUILD_FAILED;
                                execution.error = "Interrupted while waiting for TeamCity builds";
                            });
                    log.error("TEAMCITY WAIT INTERRUPTED");
                    break;
                }
            }
        }
        log.info("TEAMCITY WAIT FINISHED");
    }

    private void pollBuild(RepositoryExecution execution, String branchName) {
        TeamCityBuild build = teamCityClient.getBuild(execution.currentBuildId);
        if (!build.finished()) {
            log.debug("TEAMCITY BUILD RUNNING | buildId={} state={}", build.id(), build.state());
            return;
        }
        if (build.successful()) {
            execution.status = RepositoryReleaseStatus.BUILD_SUCCESS;
            log.info("TEAMCITY BUILD SUCCEEDED | buildId={} retried={}", build.id(), execution.buildRetried);
            return;
        }
        if (!execution.buildRetried) {
            log.warn("TEAMCITY BUILD FAILED | buildId={} status={} retrying=true", build.id(), build.status());
            TeamCityBuild retry = teamCityClient.triggerBuild(execution.repository, branchName);
            execution.buildRetried = true;
            execution.currentBuildId = retry.id();
            execution.buildIds.add(retry.id());
            log.info("TEAMCITY BUILD RETRIED | previousBuildId={} retryBuildId={} url={}",
                    build.id(), retry.id(), retry.webUrl());
            return;
        }
        execution.status = RepositoryReleaseStatus.BUILD_FAILED;
        execution.error = "TeamCity build failed after one retry with status " + build.status();
        log.error("TEAMCITY BUILD FAILED | buildId={} status={} retryExhausted=true", build.id(), build.status());
    }

    private ReleaseResult toResult(
            String operationId,
            String releaseNumber,
            Instant startedAt,
            Instant finishedAt,
            List<RepositoryExecution> executions) {
        List<RepositoryReleaseResult> repositories = executions.stream()
                .map(RepositoryExecution::toResult)
                .toList();
        long failures = repositories.stream()
                .filter(result -> result.status() == RepositoryReleaseStatus.PREPARATION_FAILED
                        || result.status() == RepositoryReleaseStatus.BUILD_FAILED)
                .count();
        long successes = repositories.stream()
                .filter(result -> result.status() == RepositoryReleaseStatus.BUILD_SUCCESS
                        || result.status() == RepositoryReleaseStatus.SKIPPED_NO_CHANGES)
                .count();
        ReleaseStatus status = failures == 0
                ? ReleaseStatus.SUCCESS
                : successes == 0 ? ReleaseStatus.FAILURE : ReleaseStatus.PARTIAL_FAILURE;
        return new ReleaseResult(
                operationId,
                releaseNumber,
                status,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                null,
                repositories);
    }

    private ReleaseResult writeReport(ReleaseResult result) {
        try {
            Path report = reportWriter.writeReleaseCreationReport(result);
            String reportPath = report.toString().replace('\\', '/');
            log.info("RELEASE CSV REPORT WRITTEN | path={}", reportPath);
            return result.withCsvReportPath(reportPath);
        } catch (RuntimeException exception) {
            log.error("RELEASE CSV REPORT FAILED | release result is preserved", exception);
            return result;
        }
    }

    private void logSummary(ReleaseResult result) {
        long skipped = result.repositories().stream()
                .filter(repository -> repository.status() == RepositoryReleaseStatus.SKIPPED_NO_CHANGES)
                .count();
        long succeeded = result.repositories().stream()
                .filter(repository -> repository.status() == RepositoryReleaseStatus.BUILD_SUCCESS)
                .count();
        long failed = result.repositories().stream()
                .filter(repository -> repository.status() == RepositoryReleaseStatus.PREPARATION_FAILED
                        || repository.status() == RepositoryReleaseStatus.BUILD_FAILED)
                .count();
        long retried = result.repositories().stream().filter(RepositoryReleaseResult::buildRetried).count();
        log.info(SEPARATOR);
        log.info("RELEASE FINISHED | operationId={} status={} durationMs={} repositories={} skipped={} succeeded={} failed={} retried={} csvReport={}",
                result.operationId(), result.status(), result.durationMillis(), result.repositories().size(),
                skipped, succeeded, failed, retried, result.csvReportPath());
        for (RepositoryReleaseResult repository : result.repositories()) {
            log.info("RELEASE RESULT | repository={} status={} branch={} pullRequestId={} buildIds={} retried={} error={}",
                    repository.repository(), repository.status(), repository.branchName(), repository.pullRequestId(),
                    repository.buildIds(), repository.buildRetried(), repository.error());
        }
        log.info(SEPARATOR);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static final class RepositoryExecution {
        private final String repository;
        private final String branchName;
        private final List<Long> buildIds = new ArrayList<>();
        private RepositoryReleaseStatus status;
        private Long pullRequestId;
        private String pullRequestUrl;
        private long currentBuildId;
        private boolean buildRetried;
        private String error;

        private RepositoryExecution(String repository, String branchName) {
            this.repository = repository;
            this.branchName = branchName;
        }

        private boolean waitingForBuild() {
            return status == RepositoryReleaseStatus.BUILD_QUEUED;
        }

        private RepositoryReleaseResult toResult() {
            return new RepositoryReleaseResult(
                    repository,
                    status,
                    branchName,
                    pullRequestId,
                    pullRequestUrl,
                    List.copyOf(buildIds),
                    buildRetried,
                    error);
        }
    }
}
