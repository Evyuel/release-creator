package com.dtim.releasecreator.client;

import com.dtim.releasecreator.exception.IntegrationException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class BitbucketRequestExecutor {

    private final RateLimiter rateLimiter;

    public BitbucketRequestExecutor(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiter = rateLimiterRegistry.rateLimiter("bitbucket");
    }

    public <T> T execute(Supplier<T> request) {
        Supplier<T> decoratedRequest =
                RateLimiter.decorateSupplier(rateLimiter, request);

        try {
            return decoratedRequest.get();
        } catch (RequestNotPermitted e) {
            throw new IntegrationException(
                    "Bitbucket API rate limit permit was not acquired",
                    e
            );
        }
    }

    public void execute(Runnable request) {
        Runnable decoratedRequest =
                RateLimiter.decorateRunnable(rateLimiter, request);

        try {
            decoratedRequest.run();
        } catch (RequestNotPermitted e) {
            throw new IntegrationException(
                    "Bitbucket API rate limit permit was not acquired",
                    e
            );
        }
    }
}
