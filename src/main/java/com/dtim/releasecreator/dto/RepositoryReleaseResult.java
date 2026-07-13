package com.dtim.releasecreator.dto;

import java.util.List;

public record RepositoryReleaseResult(
        String repository,
        RepositoryReleaseStatus status,
        String branchName,
        Long pullRequestId,
        String pullRequestUrl,
        List<Long> buildIds,
        boolean buildRetried,
        String error) {
}
