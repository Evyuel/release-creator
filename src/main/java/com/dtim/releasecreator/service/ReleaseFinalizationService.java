package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.client.BitbucketPullRequest;
import com.dtim.releasecreator.dto.FinalizationStep;
import com.dtim.releasecreator.dto.ReleaseFinalizationResult;
import com.dtim.releasecreator.dto.ReleaseStatus;
import com.dtim.releasecreator.dto.RepositoryFinalizationResult;
import com.dtim.releasecreator.dto.RepositoryFinalizationStatus;
import com.dtim.releasecreator.exception.ReleaseInProgressException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class ReleaseFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseFinalizationService.class);
    private static final String RELEASE_BRANCH_PREFIX = "release/";
    private static final String MASTER_BRANCH = "master";
    private static final String DEVELOP_BRANCH = "develop";
    private static final String SEPARATOR = "================================================================================";

    private final ReleaseVersionValidator releaseVersionValidator;
    private final ReleaseRepositoryProvider releaseRepositoryProvider;
    private final BitbucketClient bitbucketClient;
    private final ReleaseFinalizationCsvReportWriter reportWriter;
    private final AtomicBoolean finalizationInProgress = new AtomicBoolean(false);

    public ReleaseFinalizationService(
            ReleaseVersionValidator releaseVersionValidator,
            ReleaseRepositoryProvider releaseRepositoryProvider,
            BitbucketClient bitbucketClient,
            ReleaseFinalizationCsvReportWriter reportWriter) {
        this.releaseVersionValidator = releaseVersionValidator;
        this.releaseRepositoryProvider = releaseRepositoryProvider;
        this.bitbucketClient = bitbucketClient;
        this.reportWriter = reportWriter;
    }

    public ReleaseFinalizationResult finalizeRelease(String releaseNumber) {
        releaseVersionValidator.validate(releaseNumber);
        if (!finalizationInProgress.compareAndSet(false, true)) {
            throw new ReleaseInProgressException();
        }

        Instant startedAt = Instant.now();
        String operationId = UUID.randomUUID().toString().substring(0, 8);
        try (MDC.MDCCloseable releaseMdc = MDC.putCloseable("releaseNumber", releaseNumber);
                MDC.MDCCloseable operationMdc = MDC.putCloseable("operationId", operationId)) {
            log.info(SEPARATOR);
            log.info("RELEASE FINALIZATION STARTED | operationId={} releaseNumber={}", operationId, releaseNumber);
            String releaseBranch = RELEASE_BRANCH_PREFIX + releaseNumber;
            List<String> repositories = releaseRepositoryProvider.getRepositoriesForRelease();
            List<RepositoryFinalizationResult> results = new ArrayList<>();
            for (String repository : repositories) {
                results.add(finalizeRepository(repository, releaseNumber, releaseBranch));
            }

            Instant finishedAt = Instant.now();
            ReleaseFinalizationResult result = summarize(
                    operationId, releaseNumber, startedAt, finishedAt, results);
            result = writeReport(result);
            logSummary(result);
            return result;
        } finally {
            finalizationInProgress.set(false);
        }
    }

    private RepositoryFinalizationResult finalizeRepository(
            String repository,
            String releaseNumber,
            String releaseBranch) {
        Execution execution = new Execution(repository, releaseBranch);
        try (MDC.MDCCloseable repositoryMdc = MDC.putCloseable("repository", repository)) {
            log.info("REPOSITORY FINALIZATION STARTED");
            List<BitbucketPullRequest> releasePullRequests;
            try {
                releasePullRequests = bitbucketClient.findPullRequests(repository, releaseBranch, MASTER_BRANCH);
            } catch (RuntimeException exception) {
                return execution.fail(RepositoryFinalizationStatus.FAILED_UNEXPECTED_ERROR,
                        FinalizationStep.FIND_RELEASE_PR, exception).toResult();
            }

            List<BitbucketPullRequest> openReleasePullRequests = releasePullRequests.stream()
                    .filter(BitbucketPullRequest::open)
                    .toList();
            if (openReleasePullRequests.size() > 1) {
                return execution.fail(
                        RepositoryFinalizationStatus.FAILED_MULTIPLE_RELEASE_PRS,
                        FinalizationStep.FIND_RELEASE_PR,
                        "Multiple open pull requests found from " + releaseBranch + " to master")
                        .toResult();
            }

            boolean releaseWasAlreadyMerged;
            if (openReleasePullRequests.size() == 1) {
                BitbucketPullRequest releasePullRequest = openReleasePullRequests.get(0);
                execution.setReleasePullRequest(releasePullRequest);
                try {
                    BitbucketPullRequest merged = bitbucketClient.mergePullRequest(repository, releasePullRequest);
                    execution.setReleasePullRequest(merged);
                    execution.releasePullRequestMerged = true;
                    releaseWasAlreadyMerged = false;
                    log.info("RELEASE PR MERGED | pullRequestId={}", merged.id());
                } catch (RuntimeException exception) {
                    return execution.fail(RepositoryFinalizationStatus.FAILED_RELEASE_PR_MERGE,
                            FinalizationStep.MERGE_RELEASE_PR, exception).toResult();
                }
            } else {
                Optional<BitbucketPullRequest> mergedReleasePullRequest = releasePullRequests.stream()
                        .filter(BitbucketPullRequest::merged)
                        .max(Comparator.comparingLong(BitbucketPullRequest::id));
                if (mergedReleasePullRequest.isEmpty()) {
                    execution.status = RepositoryFinalizationStatus.SKIPPED_RELEASE_PR_NOT_FOUND;
                    log.info("REPOSITORY FINALIZATION SKIPPED | reason=RELEASE_PR_NOT_FOUND");
                    return execution.toResult();
                }
                execution.setReleasePullRequest(mergedReleasePullRequest.get());
                execution.releasePullRequestMerged = true;
                releaseWasAlreadyMerged = true;
                log.info("RELEASE PR ALREADY MERGED | pullRequestId={}", mergedReleasePullRequest.get().id());
            }

            List<BitbucketPullRequest> developPullRequests;
            try {
                developPullRequests = bitbucketClient.findPullRequests(repository, MASTER_BRANCH, DEVELOP_BRANCH);
            } catch (RuntimeException exception) {
                return execution.fail(RepositoryFinalizationStatus.FAILED_UNEXPECTED_ERROR,
                        FinalizationStep.FIND_DEVELOP_PR, exception).toResult();
            }

            List<BitbucketPullRequest> openDevelopPullRequests = developPullRequests.stream()
                    .filter(BitbucketPullRequest::open)
                    .toList();
            if (openDevelopPullRequests.size() > 1) {
                return execution.fail(
                        RepositoryFinalizationStatus.FAILED_MULTIPLE_DEVELOP_PRS,
                        FinalizationStep.FIND_DEVELOP_PR,
                        "Multiple open pull requests found from master to develop")
                        .toResult();
            }

            BitbucketPullRequest developPullRequest;
            if (openDevelopPullRequests.size() == 1) {
                developPullRequest = openDevelopPullRequests.get(0);
                execution.setDevelopPullRequest(developPullRequest);
                log.info("EXISTING MASTER TO DEVELOP PR REUSED | pullRequestId={}", developPullRequest.id());
            } else {
                final boolean masterAndDevelopHaveCommitsDiff;
                try {
                    masterAndDevelopHaveCommitsDiff = bitbucketClient.haveCommitsDiffer(DEVELOP_BRANCH, MASTER_BRANCH, repository);
                } catch (RuntimeException exception) {
                    return execution.fail(RepositoryFinalizationStatus.FAILED_UNEXPECTED_ERROR,
                            FinalizationStep.FIND_DEVELOP_PR, exception).toResult();
                }
                if (!masterAndDevelopHaveCommitsDiff) {
                    execution.status = releaseWasAlreadyMerged
                            ? RepositoryFinalizationStatus.SUCCESS_ALREADY_FINALIZED
                            : RepositoryFinalizationStatus.SUCCESS_DEVELOP_ALREADY_SYNCHRONIZED;
                    log.info("DEVELOP ALREADY SYNCHRONIZED | status={}", execution.status);
                    return execution.toResult();
                }
                try {
                    developPullRequest = bitbucketClient.createPullRequest(
                            repository,
                            MASTER_BRANCH,
                            DEVELOP_BRANCH,
                            "Synchronize master to develop after release " + releaseNumber,
                            "Automated synchronization after finalizing release " + releaseNumber + ".");
                    execution.setDevelopPullRequest(developPullRequest);
                    execution.developPullRequestCreated = true;
                    log.info("MASTER TO DEVELOP PR CREATED | pullRequestId={}", developPullRequest.id());
                } catch (RuntimeException exception) {
                    return execution.fail(RepositoryFinalizationStatus.FAILED_DEVELOP_PR_CREATION,
                            FinalizationStep.CREATE_DEVELOP_PR, exception).toResult();
                }
            }

            try {
                BitbucketPullRequest merged = bitbucketClient.mergePullRequest(repository, developPullRequest);
                execution.setDevelopPullRequest(merged);
                execution.developPullRequestMerged = true;
                execution.status = RepositoryFinalizationStatus.SUCCESS;
                log.info("MASTER TO DEVELOP PR MERGED | pullRequestId={}", merged.id());
            } catch (RuntimeException exception) {
                return execution.fail(RepositoryFinalizationStatus.FAILED_DEVELOP_PR_MERGE,
                        FinalizationStep.MERGE_DEVELOP_PR, exception).toResult();
            }
            return execution.toResult();
        }
    }

    private ReleaseFinalizationResult summarize(
            String operationId,
            String releaseNumber,
            Instant startedAt,
            Instant finishedAt,
            List<RepositoryFinalizationResult> repositories) {
        int successful = (int) repositories.stream().filter(result -> result.status().successful()).count();
        int skipped = (int) repositories.stream().filter(result -> result.status().skipped()).count();
        int failed = (int) repositories.stream().filter(result -> result.status().failed()).count();
        ReleaseStatus status = failed == 0
                ? ReleaseStatus.SUCCESS
                : successful == 0 ? ReleaseStatus.FAILURE : ReleaseStatus.PARTIAL_FAILURE;
        return new ReleaseFinalizationResult(
                operationId,
                releaseNumber,
                status,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                repositories.size(),
                successful,
                skipped,
                failed,
                null,
                repositories);
    }

    private ReleaseFinalizationResult writeReport(ReleaseFinalizationResult result) {
        try {
            Path path = reportWriter.writeReport(result);
            String reportPath = path.toString().replace('\\', '/');
            log.info("RELEASE FINALIZATION CSV WRITTEN | path={}", reportPath);
            return result.withCsvReportPath(reportPath);
        } catch (RuntimeException exception) {
            log.error("RELEASE FINALIZATION CSV FAILED | results are preserved", exception);
            return result;
        }
    }

    private void logSummary(ReleaseFinalizationResult result) {
        log.info(SEPARATOR);
        log.info("RELEASE FINALIZATION FINISHED | operationId={} releaseNumber={} status={} durationMs={} "
                        + "repositories={} successful={} skipped={} failed={} csvReport={}",
                result.operationId(), result.releaseNumber(), result.status(), result.durationMillis(),
                result.totalRepositories(), result.successfulCount(), result.skippedCount(),
                result.failedCount(), result.csvReportPath());
        for (RepositoryFinalizationResult repository : result.repositories()) {
            log.info("FINALIZATION RESULT | repository={} status={} releasePr={} developPr={} "
                            + "developPrCreated={} errorStep={} error={}",
                    repository.repoSlug(), repository.status(), repository.releasePullRequestId(),
                    repository.developPullRequestId(), repository.developPullRequestCreated(),
                    repository.errorStep(), repository.errorMessage());
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

    private static final class Execution {
        private final String repository;
        private final String releaseBranch;
        private RepositoryFinalizationStatus status;
        private Long releasePullRequestId;
        private String releasePullRequestUrl;
        private String releasePullRequestStatus;
        private boolean releasePullRequestMerged;
        private Long developPullRequestId;
        private String developPullRequestUrl;
        private String developPullRequestStatus;
        private boolean developPullRequestCreated;
        private boolean developPullRequestMerged;
        private FinalizationStep errorStep;
        private String errorMessage;

        private Execution(String repository, String releaseBranch) {
            this.repository = repository;
            this.releaseBranch = releaseBranch;
        }

        private void setReleasePullRequest(BitbucketPullRequest pullRequest) {
            releasePullRequestId = pullRequest.id();
            releasePullRequestUrl = pullRequest.url();
            releasePullRequestStatus = pullRequest.state();
        }

        private void setDevelopPullRequest(BitbucketPullRequest pullRequest) {
            developPullRequestId = pullRequest.id();
            developPullRequestUrl = pullRequest.url();
            developPullRequestStatus = pullRequest.state();
        }

        private Execution fail(
                RepositoryFinalizationStatus failureStatus,
                FinalizationStep step,
                Throwable throwable) {
            return fail(failureStatus, step, safeMessage(throwable));
        }

        private Execution fail(
                RepositoryFinalizationStatus failureStatus,
                FinalizationStep step,
                String message) {
            status = failureStatus;
            errorStep = step;
            errorMessage = message;
            log.error("REPOSITORY FINALIZATION FAILED | status={} step={} error={}", status, step, message);
            return this;
        }

        private RepositoryFinalizationResult toResult() {
            return new RepositoryFinalizationResult(
                    repository,
                    status,
                    releaseBranch,
                    releasePullRequestId,
                    releasePullRequestUrl,
                    releasePullRequestStatus,
                    releasePullRequestMerged,
                    developPullRequestId,
                    developPullRequestUrl,
                    developPullRequestStatus,
                    developPullRequestCreated,
                    developPullRequestMerged,
                    errorStep,
                    errorMessage);
        }
    }
}
