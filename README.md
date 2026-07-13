# Release Creator

Local Spring Boot service that creates a release across all repositories in a Bitbucket project and waits for the corresponding TeamCity builds.

## Workflow

1. Validate a release number in `XXX.Y.Z` format, for example `180.0.0`.
2. Load every repository from Bitbucket project `MYPROJ` and remove repositories from the hardcoded `ignoredRepositories` list in `ReleaseService`.
3. Before making any changes, check every selected repository for `release/<number>`. If at least one branch exists, abort the entire release.
4. For every repository:
   - skip it when there are no changes from `master` to `develop`;
   - create `release/<number>` from `develop`;
   - open a pull request into `master`;
   - queue its TeamCity build.
5. Poll all queued builds. A failed build is queued one more time; no further retries are made.
6. Return a per-repository result containing branch, pull request and build information.

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
| `TEAMCITY_BUILD_TYPE_ID_PATTERN` | `MYPROJ_{repository}_Release` |
| `TEAMCITY_POLL_INTERVAL` | `5s` |
| `TEAMCITY_WAIT_TIMEOUT` | `2h` |

The `{repository}` placeholder is replaced with the repository slug; hyphens are converted to underscores. Change the pattern if TeamCity uses another build configuration naming convention.

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

Logs are written to STDOUT. Every line contains the release and repository MDC fields, and the final block contains a complete summary.
