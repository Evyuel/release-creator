package com.dtim.releasecreator.dto;

import java.time.Instant;
import java.util.List;

public record ReleaseFinalizationResult(
        String operationId,
        String releaseNumber,
        ReleaseStatus status,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        int totalRepositories,
        int successfulCount,
        int skippedCount,
        int failedCount,
        String csvReportPath,
        List<RepositoryFinalizationResult> repositories) {

    public ReleaseFinalizationResult {
        repositories = List.copyOf(repositories);
    }

    public ReleaseFinalizationResult withCsvReportPath(String path) {
        return new ReleaseFinalizationResult(
                operationId,
                releaseNumber,
                status,
                startedAt,
                finishedAt,
                durationMillis,
                totalRepositories,
                successfulCount,
                skippedCount,
                failedCount,
                path,
                repositories);
    }
}
