package com.dtim.releasecreator.dto;

public enum RepositoryFinalizationStatus {
    SUCCESS,
    SUCCESS_ALREADY_FINALIZED,
    SUCCESS_DEVELOP_ALREADY_SYNCHRONIZED,
    SKIPPED_RELEASE_PR_NOT_FOUND,
    FAILED_MULTIPLE_RELEASE_PRS,
    FAILED_MULTIPLE_DEVELOP_PRS,
    FAILED_RELEASE_PR_MERGE,
    FAILED_DEVELOP_PR_CREATION,
    FAILED_DEVELOP_PR_MERGE,
    FAILED_UNEXPECTED_ERROR;

    public boolean successful() {
        return name().startsWith("SUCCESS");
    }

    public boolean skipped() {
        return name().startsWith("SKIPPED");
    }

    public boolean failed() {
        return name().startsWith("FAILED");
    }
}
