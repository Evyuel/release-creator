package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.client.BitbucketPullRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseSemVerCommitCreator {
    private static final String GIT_IGNORE_FILE_PATH = ".gitignore";
    private static final String ADDITIONAL_COMMIT_BRANCH_NAME_PATTERN = "feature/prepare_for_%s_release";
    private static final String COMMIT_MESSAGE_PATTERN = "feat: %s: trigger semver for release %s";
    private final BitbucketClient bitbucketClient;

    public void addSemVerFeatCommitToRelease(String repository,
                                             String releaseBranchName,
                                             String releaseNumber,
                                             String releaseTaskNumber) {
        String releaseSimpleNumber = releaseNumber.split("\\.")[0];
        String additionalCommitBranchName = String.format(ADDITIONAL_COMMIT_BRANCH_NAME_PATTERN, releaseSimpleNumber);
        bitbucketClient.createBranch(repository, additionalCommitBranchName, releaseBranchName);

        String rawFile = bitbucketClient.getRawFile(repository, GIT_IGNORE_FILE_PATH, additionalCommitBranchName);

        String commitMessage = String.format(COMMIT_MESSAGE_PATTERN, releaseTaskNumber, releaseNumber);
        bitbucketClient.commitUpdatedFile(repository, GIT_IGNORE_FILE_PATH, additionalCommitBranchName, rawFile + " ", commitMessage);
        bitbucketClient.commitUpdatedFile(repository, GIT_IGNORE_FILE_PATH, additionalCommitBranchName, rawFile, commitMessage);

        BitbucketPullRequest pr = bitbucketClient.createPullRequest(
                repository,
                additionalCommitBranchName,
                releaseBranchName,
                String.format("Additional commit for %s release", releaseNumber),
                String.format("Additional commit for %s release", releaseNumber)
        );

        bitbucketClient.mergePullRequest(repository, pr);
        log.info("ADDITIONAL COMMIT WITH MESSAGE \"{}\" ADDED FOR RELEASE | pullRequestId={} url={}", commitMessage, pr.id(), pr.url());
    }
}