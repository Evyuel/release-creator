package com.dtim.releasecreator.dto;

public record RepositoryFinalizationResult(
        String repoSlug,
        RepositoryFinalizationStatus status,
        String releaseBranch,
        Long releasePullRequestId,
        String releasePullRequestUrl,
        String releasePullRequestStatus,
        boolean releasePullRequestMerged,
        Long developPullRequestId,
        String developPullRequestUrl,
        String developPullRequestStatus,
        boolean developPullRequestCreated,
        boolean developPullRequestMerged,
        FinalizationStep errorStep,
        String errorMessage) {
}
