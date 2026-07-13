package com.dtim.releasecreator.dto;

import java.time.Instant;
import java.util.List;

public record ReleaseResult(
        String operationId,
        String releaseNumber,
        ReleaseStatus status,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        String csvReportPath,
        List<RepositoryReleaseResult> repositories) {

    public ReleaseResult {
        repositories = List.copyOf(repositories);
    }

    public ReleaseResult withCsvReportPath(String path) {
        return new ReleaseResult(
                operationId,
                releaseNumber,
                status,
                startedAt,
                finishedAt,
                durationMillis,
                path,
                repositories);
    }
}
