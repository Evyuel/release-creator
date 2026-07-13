package com.dtim.releasecreator.dto;

import java.util.List;

public record ReleaseDeploymentResult(
        String releaseVersion,
        String environment,
        int totalServices,
        int startedCount,
        int failedCount,
        String csvReportPath,
        List<ServiceDeploymentResult> services) {

    public ReleaseDeploymentResult {
        services = List.copyOf(services);
    }

    public ReleaseDeploymentResult withCsvReportPath(String path) {
        return new ReleaseDeploymentResult(
                releaseVersion,
                environment,
                totalServices,
                startedCount,
                failedCount,
                path,
                services);
    }
}
