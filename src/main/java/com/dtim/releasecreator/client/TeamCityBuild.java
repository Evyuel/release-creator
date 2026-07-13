package com.dtim.releasecreator.client;

public record TeamCityBuild(long id, String state, String status, String webUrl) {

    public boolean finished() {
        return "finished".equalsIgnoreCase(state);
    }

    public boolean successful() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
