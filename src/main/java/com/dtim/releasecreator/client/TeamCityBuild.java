package com.dtim.releasecreator.client;

public record TeamCityBuild(
        long id,
        String buildTypeId,
        String number,
        String status,
        String state,
        String branchName,
        String webUrl) {

    public TeamCityBuild(long id, String state, String status, String webUrl) {
        this(id, null, null, status, state, null, webUrl);
    }

    public boolean finished() {
        return "finished".equalsIgnoreCase(state);
    }

    public boolean successful() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
