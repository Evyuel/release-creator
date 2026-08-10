package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.TeamCityBuild;
import com.dtim.releasecreator.client.TeamCityClient;
import com.dtim.releasecreator.config.TeamCityProperties;
import com.dtim.releasecreator.dto.DeploymentStatus;
import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.dto.ServiceDeploymentResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class ReleaseDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseDeploymentService.class);
    private static final String RELEASE_BRANCH_PREFIX = "release/";
    private static final String ENVIRONMENT = "UAT";
    private static final String SEPARATOR = "================================================================================";
    private static final String REPOSITORY_SEPARATOR = "--------------------------------------------------------------------------------";
    private static final String CONFIG_SERVER_REPO_NAME = "config-server";
    private static final String LIQUIBASE_REPO_NAME = "liquibase";
    private static final String BUSINESS_SETTINGS_API_REPO_NAME = "business-settings-api";
    private static final String GATEWAY_REPO_NAME = "gateway";
    private static final String FRONT_SERVICE_REPO_NAME = "front-service";

    private final ReleaseValidator releaseValidator;
    private final ReleaseRepositoryProvider releaseRepositoryProvider;
    private final TeamCityClient teamCityClient;
    private final TeamCityProperties teamCityProperties;
    private final ProductionReleaseBuildService productionReleaseBuildService;
    private final ReleaseDeploymentCsvReportWriter reportWriter;

    public ReleaseDeploymentService(
            ReleaseValidator releaseValidator,
            ReleaseRepositoryProvider releaseRepositoryProvider,
            TeamCityClient teamCityClient,
            TeamCityProperties teamCityProperties,
            ProductionReleaseBuildService productionReleaseBuildService,
            ReleaseDeploymentCsvReportWriter reportWriter) {
        this.releaseValidator = releaseValidator;
        this.releaseRepositoryProvider = releaseRepositoryProvider;
        this.teamCityClient = teamCityClient;
        this.teamCityProperties = teamCityProperties;
        this.productionReleaseBuildService = productionReleaseBuildService;
        this.reportWriter = reportWriter;
    }

    public ReleaseDeploymentResult deployToUat(String releaseVersion) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("releaseVersion", releaseVersion)) {
            releaseValidator.validateVersion(releaseVersion);
            String releaseBranch = RELEASE_BRANCH_PREFIX + releaseVersion;
            logStart(releaseVersion, releaseBranch);

            List<String> repositories = releaseRepositoryProvider.getRepositoriesForRelease();
            List<ServiceDeploymentResult> services = new ArrayList<>();
            int stageNumber = 0;
            List<DeploymentStage> stages = deploymentStages(repositories);
            for (DeploymentStage stage : stages) {
                stageNumber++;
                services.addAll(deployStage(stage, stageNumber, stages.size(), releaseVersion, releaseBranch));
            }

            int startedCount = (int) services.stream()
                    .filter(service -> service.teamCityDeployBuildId() != null)
                    .count();
            int successfulCount = (int) services.stream()
                    .filter(service -> service.status() == DeploymentStatus.SUCCESS)
                    .count();
            ReleaseDeploymentResult result = new ReleaseDeploymentResult(
                    releaseVersion,
                    ENVIRONMENT,
                    services.size(),
                    startedCount,
                    successfulCount,
                    services.size() - successfulCount,
                    null,
                    services);
            result = writeReport(result);
            logFinished(result);
            return result;
        }
    }

    private List<DeploymentStage> deploymentStages(List<String> repositories) {
        List<String> otherRepositories = repositories.stream()
                .filter(repository -> !isSpecialRepository(repository))
                .toList();
        List<DeploymentStage> stages = new ArrayList<>();
        addStageIfPresent(stages, "CONFIG_SERVER", repositories, CONFIG_SERVER_REPO_NAME);
        addStageIfPresent(stages, "LIQUIBASE", repositories, LIQUIBASE_REPO_NAME);
        addStageIfPresent(stages, "BUSINESS_SETTINGS_API", repositories, BUSINESS_SETTINGS_API_REPO_NAME);
        if (!otherRepositories.isEmpty()) {
            stages.add(new DeploymentStage("OTHER_SERVICES", otherRepositories));
        }
        addStageIfPresent(stages, "GATEWAY", repositories, GATEWAY_REPO_NAME);
        addStageIfPresent(stages, "FRONT_SERVICE", repositories, FRONT_SERVICE_REPO_NAME);
        return stages;
    }

    private void addStageIfPresent(
            List<DeploymentStage> stages,
            String name,
            List<String> repositories,
            String repository) {
        if (repositories.contains(repository)) {
            stages.add(new DeploymentStage(name, List.of(repository)));
        }
    }

    private boolean isSpecialRepository(String repository) {
        return CONFIG_SERVER_REPO_NAME.equals(repository)
                || BUSINESS_SETTINGS_API_REPO_NAME.equals(repository)
                || GATEWAY_REPO_NAME.equals(repository)
                || FRONT_SERVICE_REPO_NAME.equals(repository)
                || LIQUIBASE_REPO_NAME.equals(repository);
    }

    private List<ServiceDeploymentResult> deployStage(
            DeploymentStage stage,
            int stageNumber,
            int totalStages,
            String releaseVersion,
            String releaseBranch) {
        log.info("UAT DEPLOYMENT STAGE STARTED | stage={}/{} name={} repositories={}",
                stageNumber, totalStages, stage.name(), stage.repositories());
        List<DeploymentExecution> executions = stage.repositories().stream()
                .map(repository -> startDeployment(repository, releaseVersion, releaseBranch))
                .toList();
        waitForDeployments(stage, executions);
        log.info("UAT DEPLOYMENT STAGE FINISHED | stage={}/{} name={}",
                stageNumber, totalStages, stage.name());
        return executions.stream().map(DeploymentExecution::toResult).toList();
    }

    private DeploymentExecution startDeployment(
            String repository,
            String releaseVersion,
            String releaseBranch) {
        DeploymentExecution execution = new DeploymentExecution(repository);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("repository", repository)) {
            log.info(REPOSITORY_SEPARATOR);
            log.info("UAT DEPLOYMENT: {}", repository);
            log.info(REPOSITORY_SEPARATOR);

            String sourceBuildTypeId = productionReleaseBuildService.getBuildTypeForRepo(repository);
            if (sourceBuildTypeId.isBlank()) {
                return execution.fail("Source buildTypeId is not configured for repo: " + repository);
            }

            log.info("[1/3] Searching latest successful TeamCity build for branch {}", releaseBranch);
            Optional<TeamCityBuild> sourceBuildOptional =
                    teamCityClient.findLatestSuccessfulBuild(sourceBuildTypeId, releaseBranch);
            if (sourceBuildOptional.isEmpty()) {
                return execution.fail("Successful source build not found for branch " + releaseBranch);
            }
            execution.sourceBuild = sourceBuildOptional.get();
            log.info("[OK] Source build found: id={}, number={}",
                    execution.sourceBuild.id(), execution.sourceBuild.number());

            log.info("[2/3] Triggering UAT deploy build");
            execution.deploymentBuild = teamCityClient.triggerUatDeployBuild(
                    productionReleaseBuildService.getTST1DeployTypeForRepo(repository),
                    repository,
                    releaseVersion,
                    execution.sourceBuild);
            log.info("[OK] UAT deploy build started: id={}, url={}",
                    execution.deploymentBuild.id(), execution.deploymentBuild.webUrl());
            return execution;
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            log.error("[FAILED] UAT deployment could not be started: {}", message, exception);
            return execution.fail(message);
        }
    }

    private void waitForDeployments(DeploymentStage stage, List<DeploymentExecution> executions) {
        List<DeploymentExecution> pending = executions.stream()
                .filter(DeploymentExecution::pending)
                .toList();
        if (pending.isEmpty()) {
            log.info("UAT DEPLOYMENT WAIT SKIPPED | stage={} no builds were queued", stage.name());
            return;
        }

        Instant deadline = Instant.now().plus(teamCityProperties.waitTimeout());
        log.info("[3/3] Waiting for UAT deployments | stage={} builds={} timeout={}",
                stage.name(), pending.size(), teamCityProperties.waitTimeout());
        while (pending.stream().anyMatch(DeploymentExecution::pending)) {
            if (!Instant.now().isBefore(deadline)) {
                pending.stream()
                        .filter(DeploymentExecution::pending)
                        .forEach(execution -> execution.fail(
                                "Timed out waiting for TeamCity UAT deployment build "
                                        + execution.deploymentBuild.id()));
                break;
            }

            for (DeploymentExecution execution : pending) {
                if (!execution.pending()) {
                    continue;
                }
                try (MDC.MDCCloseable ignored = MDC.putCloseable("repository", execution.repository)) {
                    TeamCityBuild build = teamCityClient.getBuild(execution.deploymentBuild.id());
                    execution.deploymentBuild = build;
                    if (!build.finished()) {
                        log.debug("UAT deployment is running | buildId={} state={}", build.id(), build.state());
                    } else if (build.successful()) {
                        execution.status = DeploymentStatus.SUCCESS;
                        log.info("[OK] UAT deployment succeeded | buildId={}", build.id());
                    } else {
                        execution.fail("TeamCity UAT deployment failed with status " + build.status());
                    }
                } catch (RuntimeException exception) {
                    execution.fail("TeamCity UAT deployment polling failed: " + safeMessage(exception));
                    log.error("[FAILED] Could not poll UAT deployment", exception);
                }
            }

            if (pending.stream().anyMatch(DeploymentExecution::pending)) {
                try {
                    Thread.sleep(Math.max(1L, teamCityProperties.pollInterval().toMillis()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    pending.stream()
                            .filter(DeploymentExecution::pending)
                            .forEach(execution -> execution.fail(
                                    "Interrupted while waiting for TeamCity UAT deployment"));
                    break;
                }
            }
        }
    }

    private ReleaseDeploymentResult writeReport(ReleaseDeploymentResult result) {
        try {
            Path report = reportWriter.writeUatDeploymentReport(result);
            String reportPath = report.toString().replace('\\', '/');
            log.info("[OK] UAT deployment CSV report written: {}", reportPath);
            return result.withCsvReportPath(reportPath);
        } catch (RuntimeException exception) {
            log.error("[FAILED] UAT deployment CSV report could not be written; deployment results are preserved",
                    exception);
            return result;
        }
    }

    private void logStart(String releaseVersion, String releaseBranch) {
        log.info(SEPARATOR);
        log.info("RELEASE DEPLOYMENT TO UAT STARTED");
        log.info(SEPARATOR);
        log.info("Release version : {}", releaseVersion);
        log.info("Release branch  : {}", releaseBranch);
        log.info("Environment     : {}", ENVIRONMENT);
        log.info(SEPARATOR);
    }

    private void logFinished(ReleaseDeploymentResult result) {
        log.info(SEPARATOR);
        log.info("RELEASE DEPLOYMENT TO UAT FINISHED");
        log.info(SEPARATOR);
        log.info("Release version : {}", result.releaseVersion());
        log.info("Environment     : {}", result.environment());
        log.info("Total services  : {}", result.totalServices());
        log.info("Started         : {}", result.startedCount());
        log.info("Successful      : {}", result.successfulCount());
        log.info("Failed          : {}", result.failedCount());
        log.info("CSV report      : {}", result.csvReportPath());
        log.info(SEPARATOR);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private record DeploymentStage(String name, List<String> repositories) {
    }

    private static final class DeploymentExecution {
        private final String repository;
        private TeamCityBuild sourceBuild;
        private TeamCityBuild deploymentBuild;
        private DeploymentStatus status;
        private String errorMessage;

        private DeploymentExecution(String repository) {
            this.repository = repository;
        }

        private boolean pending() {
            return deploymentBuild != null && status == null;
        }

        private DeploymentExecution fail(String message) {
            status = DeploymentStatus.FAILED;
            errorMessage = message;
            log.error("[FAILED] {}", message);
            return this;
        }

        private ServiceDeploymentResult toResult() {
            if (status == DeploymentStatus.SUCCESS) {
                return ServiceDeploymentResult.successful(repository, sourceBuild, deploymentBuild);
            }
            return ServiceDeploymentResult.failed(repository, sourceBuild, deploymentBuild, errorMessage);
        }
    }
}
