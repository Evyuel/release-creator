package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.TeamCityBuild;
import com.dtim.releasecreator.client.TeamCityClient;
import com.dtim.releasecreator.dto.DeploymentStatus;
import com.dtim.releasecreator.dto.ReleaseDeploymentResult;
import com.dtim.releasecreator.dto.ServiceDeploymentResult;
import java.nio.file.Path;
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

    private final ReleaseValidator releaseValidator;
    private final ReleaseRepositoryProvider releaseRepositoryProvider;
    private final TeamCityClient teamCityClient;
    private final ProductionReleaseBuildService productionReleaseBuildService;
    private final ReleaseDeploymentCsvReportWriter reportWriter;

    public ReleaseDeploymentService(
            ReleaseValidator releaseValidator,
            ReleaseRepositoryProvider releaseRepositoryProvider,
            TeamCityClient teamCityClient,
            ProductionReleaseBuildService productionReleaseBuildService,
            ReleaseDeploymentCsvReportWriter reportWriter) {
        this.releaseValidator = releaseValidator;
        this.releaseRepositoryProvider = releaseRepositoryProvider;
        this.teamCityClient = teamCityClient;
        this.productionReleaseBuildService = productionReleaseBuildService;
        this.reportWriter = reportWriter;
    }

    public ReleaseDeploymentResult deployToUat(String releaseVersion) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("releaseNumber", releaseVersion)) {
            releaseValidator.validateVersion(releaseVersion);
            String releaseBranch = RELEASE_BRANCH_PREFIX + releaseVersion;
            logStart(releaseVersion, releaseBranch);

            List<String> repositories = releaseRepositoryProvider.getRepositoriesForRelease();
            List<ServiceDeploymentResult> services = new ArrayList<>();
            for (String repository : repositories) {
                services.add(deployRepository(repository, releaseVersion, releaseBranch));
            }

            int startedCount = (int) services.stream()
                    .filter(service -> service.status() == DeploymentStatus.STARTED)
                    .count();
            ReleaseDeploymentResult result = new ReleaseDeploymentResult(
                    releaseVersion,
                    ENVIRONMENT,
                    services.size(),
                    startedCount,
                    services.size() - startedCount,
                    null,
                    services);
            result = writeReport(result);
            logFinished(result);
            return result;
        }
    }

    private ServiceDeploymentResult deployRepository(
            String repository,
            String releaseVersion,
            String releaseBranch) {
        TeamCityBuild sourceBuild = null;
        try (MDC.MDCCloseable ignored = MDC.putCloseable("repository", repository)) {
            log.info(REPOSITORY_SEPARATOR);
            log.info("UAT DEPLOYMENT: {}", repository);
            log.info(REPOSITORY_SEPARATOR);

            String sourceBuildTypeId = productionReleaseBuildService.getBuildTypeForRepo(repository);
            if (sourceBuildTypeId.isBlank()) {
                return failed(repository, "Source buildTypeId is not configured for repo: " + repository);
            }

            log.info("[1/1] Searching latest successful TeamCity build for branch {}", releaseBranch);
            Optional<TeamCityBuild> sourceBuildOptional =
                    teamCityClient.findLatestSuccessfulBuild(sourceBuildTypeId, releaseBranch);
            if (sourceBuildOptional.isEmpty()) {
                return failed(repository, "Successful source build not found for branch " + releaseBranch);
            }
            sourceBuild = sourceBuildOptional.get();
            log.info("[OK] Source build found: id={}, number={}", sourceBuild.id(), sourceBuild.number());

            log.info("[2/2] Triggering UAT deploy build");
            TeamCityBuild deploymentBuild = teamCityClient.triggerUatDeployBuild(
                    productionReleaseBuildService.getTST1DeployTypeForRepo(repository),
                    releaseBranch,
                    repository,
                    releaseVersion,
                    sourceBuild);
            log.info("[OK] UAT deploy build started: id={}, url={}",
                    deploymentBuild.id(), deploymentBuild.webUrl());
            return ServiceDeploymentResult.started(repository, sourceBuild, deploymentBuild);
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            log.error("[FAILED] UAT deployment could not be started: {}", message, exception);
            return ServiceDeploymentResult.failed(repository, sourceBuild, message);
        }
    }

    private ServiceDeploymentResult failed(String repository, String message) {
        log.error("[FAILED] {}", message);
        return ServiceDeploymentResult.failed(repository, message);
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
}
