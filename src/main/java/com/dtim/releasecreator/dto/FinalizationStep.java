package com.dtim.releasecreator.dto;

public enum FinalizationStep {
    FIND_RELEASE_PR,
    MERGE_RELEASE_PR,
    FIND_DEVELOP_PR,
    CREATE_DEVELOP_PR,
    MERGE_DEVELOP_PR
}
