package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.BitbucketClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReleaseRepositoryProvider {

    private static final Logger log = LoggerFactory.getLogger(ReleaseRepositoryProvider.class);

    /* Add repository slugs to this list. It is intentionally maintained in code for now. */
    private final List<String> ignoredRepositories = List.of();

    private final BitbucketClient bitbucketClient;

    public ReleaseRepositoryProvider(BitbucketClient bitbucketClient) {
        this.bitbucketClient = bitbucketClient;
    }

    public List<String> getRepositoriesForRelease() {
        List<String> allRepositories = bitbucketClient.getRepositoryNames();
        List<String> repositories = allRepositories.stream()
                .filter(repository -> !ignoredRepositories.contains(repository))
                .sorted()
                .toList();
        List<String> ignored = allRepositories.stream()
                .filter(ignoredRepositories::contains)
                .sorted()
                .toList();
        log.info("REPOSITORIES LOADED | total={} selected={} ignored={} ignoredRepositories={}",
                allRepositories.size(), repositories.size(), ignored.size(), ignored);
        return repositories;
    }
}
