# Release Creator

Local Spring Boot service that creates a release across all repositories in a Bitbucket project and waits for the corresponding TeamCity builds.

## Workflow

1. Validate a release number in `XXX.Y.Z` format, for example `180.0.0`.
2. Load only active repositories from Bitbucket project `MYPROJ` and remove repositories configured in `integrations.excluded-repositories`.
3. Before making any changes, check every selected repository for `release/<number>`. If at least one branch exists, abort the entire release.
4. For every repository:
   - skip it when there are no changes from `master` to `develop`;
   - create `release/<number>` from `develop`;
   - open a pull request into `master`;
   - queue its TeamCity build.
5. Poll all queued builds. A failed build is queued one more time; no further retries are made.
6. Write a new CSV report and return a per-repository result containing branch, pull request and build information.

Only one release can run in one service instance at a time.

## Configuration

All secrets and endpoints are provided through environment variables:

| Variable | Default |
| --- | --- |
| `BITBUCKET_BASE_URL` | `http://localhost:7990` |
| `BITBUCKET_USERNAME` | empty; when set, the token is used as a Basic Auth password |
| `BITBUCKET_TOKEN` | empty; used as a Bearer token when username is empty |
| `BITBUCKET_PROJECT_KEY` | `MYPROJ` |
| `BITBUCKET_DEVELOP_BRANCH` | `develop` |
| `BITBUCKET_MASTER_BRANCH` | `master` |
| `TEAMCITY_BASE_URL` | `http://localhost:8111` |
| `TEAMCITY_TOKEN` | empty |
| `TEAMCITY_POLL_INTERVAL` | `5s` |
| `TEAMCITY_WAIT_TIMEOUT` | `2h` |

Excluded repositories are configured as a YAML list. They are removed before branch checks and are not used by either release creation or UAT deployment:

```yaml
integrations:
  excluded-repositories:
    - legacy-service
    - archived-adapter
```

TeamCity build type IDs are resolved by `ProductionReleaseBuildService`. Repository slugs are converted to PascalCase and receive the suffix for the required operation: `_Deployment_ReleaseProduction`, `_Module_Build`, or `_Deployment_DeployIntegration`. Non-standard IDs are maintained in the corresponding exception maps in that service.

## Run

```shell
./gradlew bootRun
```

Create a release:

```shell
curl -X POST http://localhost:8080/api/v1/releases \
  -H "Content-Type: application/json" \
  -d '{"releaseNumber":"180.0.0"}'
```

Deploy release artifacts to UAT:

```shell
curl -X POST http://localhost:8080/api/releases/180.0.0/deployments/uat
```

Finalize a release:

```shell
curl -X POST http://localhost:8080/api/v1/releases/181.0.0/finalize
```

Finalization processes the same active, non-excluded repository list as release creation. For each repository it finds the exact `release/<number> -> master` pull request, checks Bitbucket mergeability, merges it, then reuses or creates and merges a `master -> develop` pull request. A repository without a release pull request is skipped, while a repository-level failure is recorded without stopping the remaining repositories.

The operation is idempotent: an already merged release pull request is recognized, an existing open `master -> develop` pull request is reused, and a repository with no remaining `master -> develop` changes is reported as already finalized or synchronized. Merge vetoes are not bypassed and merge operations are not automatically retried.

Every completed finalization writes a separate UTF-8, semicolon-separated CSV report under `reports/release-finalizing`, for example `release-finalizing-181.0.0-20260715-132030-f82ab3c1.csv`. The response includes overall counters, PR identifiers and URLs, per-repository statuses, the failed step and `csvReportPath`. A CSV failure does not discard already completed Bitbucket operations.

The UAT endpoint finds the latest successful finished source build for each release branch and queues the configured deploy build with source-build parameters. It does not wait for deploy completion. A failure in one repository is returned for that repository without stopping the others.

Every UAT deployment attempt also writes a UTF-8, semicolon-separated CSV report under `reports/deployments`. Values containing semicolons, quotes, or line breaks are CSV-escaped. If report writing fails, already queued deployments remain in the response and `csvReportPath` is `null`.

Every release-creation attempt writes a separate UTF-8, semicolon-separated report under `reports/releases`, for example `release-creation-180.0.0-20260713-143015-a8f31c42.csv`. Its columns are:

```text
operationId;releaseVersion;releaseStatus;startedAt;finishedAt;durationMs;repoSlug;status;skipReason;releaseBranch;pullRequestId;pullRequestUrl;initialBuildId;retryBuildId;buildRetried;errorMessage
```

The response contains the generated path in `csvReportPath`. Report-writing failure does not discard the release result; in that case the path is `null` and the failure is logged.

Logs are written both to colorized STDOUT and `logs/release-creator.log`. Every line contains the release and repository MDC fields. File logs are rotated daily or at 20 MB, retained for 30 days, and capped at 1 GB total.
