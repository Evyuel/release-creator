package com.dtim.releasecreator.dto;

import com.dtim.releasecreator.client.TeamCityBuild;

public record ServiceDeploymentResult(
        String repoSlug,
        DeploymentStatus status,
        Long sourceBuildId,
        String sourceBuildNumber,
        String sourceBuildUrl,
        Long teamCityDeployBuildId,
        String teamCityDeployBuildUrl,
        String errorMessage) {

    public static ServiceDeploymentResult started(
            String repoSlug,
            TeamCityBuild sourceBuild,
            TeamCityBuild deploymentBuild) {
        return new ServiceDeploymentResult(
                repoSlug,
                DeploymentStatus.STARTED,
                sourceBuild.id(),
                sourceBuild.number(),
                sourceBuild.webUrl(),
                deploymentBuild.id(),
                deploymentBuild.webUrl(),
                null);
    }

    public static ServiceDeploymentResult successful(
            String repoSlug,
            TeamCityBuild sourceBuild,
            TeamCityBuild deploymentBuild) {
        return new ServiceDeploymentResult(
                repoSlug,
                DeploymentStatus.SUCCESS,
                sourceBuild.id(),
                sourceBuild.number(),
                sourceBuild.webUrl(),
                deploymentBuild.id(),
                deploymentBuild.webUrl(),
                null);
    }

    public static ServiceDeploymentResult failed(String repoSlug, String errorMessage) {
        return failed(repoSlug, null, errorMessage);
    }

    public static ServiceDeploymentResult failed(
            String repoSlug,
            TeamCityBuild sourceBuild,
            String errorMessage) {
        return failed(repoSlug, sourceBuild, null, errorMessage);
    }

    public static ServiceDeploymentResult failed(
            String repoSlug,
            TeamCityBuild sourceBuild,
            TeamCityBuild deploymentBuild,
            String errorMessage) {
        return new ServiceDeploymentResult(
                repoSlug,
                DeploymentStatus.FAILED,
                sourceBuild == null ? null : sourceBuild.id(),
                sourceBuild == null ? null : sourceBuild.number(),
                sourceBuild == null ? null : sourceBuild.webUrl(),
                deploymentBuild == null ? null : deploymentBuild.id(),
                deploymentBuild == null ? null : deploymentBuild.webUrl(),
                errorMessage);
    }
}
