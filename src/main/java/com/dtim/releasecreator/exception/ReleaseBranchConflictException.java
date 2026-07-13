package com.dtim.releasecreator.exception;

import java.util.List;

public class ReleaseBranchConflictException extends RuntimeException {

    private final List<String> repositories;

    public ReleaseBranchConflictException(String branchName, List<String> repositories) {
        super("Branch " + branchName + " already exists in " + repositories.size() + " repositories");
        this.repositories = List.copyOf(repositories);
    }

    public List<String> getRepositories() {
        return repositories;
    }
}
