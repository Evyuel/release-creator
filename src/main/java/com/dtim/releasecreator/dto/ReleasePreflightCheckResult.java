package com.dtim.releasecreator.dto;

public record ReleasePreflightCheckResult(
        String repository,
        ReleasePreflightCheck check,
        String sourceBranch,
        String targetBranch,
        ReleasePreflightCheckStatus status,
        String message) {

    public boolean blocksRelease() {
        return status == ReleasePreflightCheckStatus.FAILED
                || status == ReleasePreflightCheckStatus.ERROR;
    }
}
