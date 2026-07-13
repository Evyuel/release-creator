package com.dtim.releasecreator.dto;

public enum RepositoryReleaseStatus {
    SKIPPED_NO_CHANGES,
    PREPARATION_FAILED,
    BUILD_QUEUED,
    BUILD_SUCCESS,
    BUILD_FAILED
}
