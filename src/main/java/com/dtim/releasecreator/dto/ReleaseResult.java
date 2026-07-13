package com.dtim.releasecreator.dto;

import java.time.Instant;
import java.util.List;

public record ReleaseResult(
        String releaseNumber,
        ReleaseStatus status,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        List<RepositoryReleaseResult> repositories) {
}
