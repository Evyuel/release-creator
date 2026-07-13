package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.config.IntegrationsProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ReleaseRepositoryProvider {

    private static final Logger log = LoggerFactory.getLogger(ReleaseRepositoryProvider.class);

    private final BitbucketClient bitbucketClient;
    private final IntegrationsProperties integrationsProperties;

    public ReleaseRepositoryProvider(
            BitbucketClient bitbucketClient,
            IntegrationsProperties integrationsProperties) {
        this.bitbucketClient = bitbucketClient;
        this.integrationsProperties = integrationsProperties;
    }

    public List<String> getRepositoriesForRelease() {
        List<String> activeRepositories = bitbucketClient.getRepositoryNames();
        List<String> excludedRepositories = integrationsProperties.excludedRepositories();
        List<String> repositories = activeRepositories.stream()
                .filter(repository -> !excludedRepositories.contains(repository))
                .sorted()
                .toList();
        List<String> excluded = activeRepositories.stream()
                .filter(excludedRepositories::contains)
                .sorted()
                .toList();
        log.info("REPOSITORIES SELECTED | active={} selected={} excluded={} excludedRepositories={}",
                activeRepositories.size(), repositories.size(), excluded.size(), excluded);
        return repositories;
    }
}
