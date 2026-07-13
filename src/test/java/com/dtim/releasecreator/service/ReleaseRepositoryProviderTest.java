package com.dtim.releasecreator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.config.IntegrationsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseRepositoryProviderTest {

    @Test
    void excludesConfiguredRepositoriesAndSortsTheRemainingOnes() {
        BitbucketClient bitbucketClient = mock(BitbucketClient.class);
        when(bitbucketClient.getRepositoryNames()).thenReturn(List.of("zeta", "ignored-two", "alpha", "ignored-one"));
        ReleaseRepositoryProvider provider = new ReleaseRepositoryProvider(
                bitbucketClient,
                new IntegrationsProperties(List.of(" ignored-one ", "ignored-two", "ignored-two")));

        assertThat(provider.getRepositoriesForRelease()).containsExactly("alpha", "zeta");
    }
}
