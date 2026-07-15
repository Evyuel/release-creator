package com.dtim.releasecreator.client;

public record BitbucketPullRequest(
        long id,
        int version,
        String state,
        String fromRef,
        String toRef,
        String url) {

    public boolean open() {
        return "OPEN".equalsIgnoreCase(state);
    }

    public boolean merged() {
        return "MERGED".equalsIgnoreCase(state);
    }
}
