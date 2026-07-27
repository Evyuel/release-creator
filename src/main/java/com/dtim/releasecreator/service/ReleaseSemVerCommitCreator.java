package com.dtim.releasecreator.service;

import com.dtim.releasecreator.client.BitbucketClient;
import com.dtim.releasecreator.client.BitbucketPullRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseSemVerCommitCreator {
    private static final String RELEASE_FILE_PATH = ".release-creator";
    private static final String ADDITIONAL_COMMIT_BRANCH_NAME_PATTERN = "release_prepare/%s";
    private static final String COMMIT_MESSAGE_PATTERN = "feat: %s: trigger semver for release %s";
    private final BitbucketClient bitbucketClient;

    public void addSemVerFeatCommitToRelease(String repository,
                                             String releaseBranchName,
                                             String releaseNumber,
                                             String releaseTaskNumber) {
        log.info("ADDING ADDITIONAL COMMIT FOR RELEASE");
        String additionalCommitBranchName = String.format(ADDITIONAL_COMMIT_BRANCH_NAME_PATTERN, releaseNumber);
        bitbucketClient.createBranch(repository, additionalCommitBranchName, releaseBranchName);
        String commitMessage = String.format(COMMIT_MESSAGE_PATTERN, releaseTaskNumber, releaseNumber);

        Optional<String> releaseFileContent = bitbucketClient.getRawFile(repository, RELEASE_FILE_PATH, additionalCommitBranchName);
        releaseFileContent.ifPresentOrElse((fileContent) -> {
                    bitbucketClient.commitUpdatedFile(repository, RELEASE_FILE_PATH, additionalCommitBranchName, releaseNumber, commitMessage);
                    bitbucketClient.commitUpdatedFile(repository, RELEASE_FILE_PATH, additionalCommitBranchName, "", commitMessage);
                },
                () -> {
                    bitbucketClient.addNewFile(repository, RELEASE_FILE_PATH, additionalCommitBranchName, releaseNumber, commitMessage);
                    bitbucketClient.commitUpdatedFile(repository, RELEASE_FILE_PATH, additionalCommitBranchName, "", commitMessage);
                }
        );

        BitbucketPullRequest pr = bitbucketClient.createPullRequest(
                repository,
                additionalCommitBranchName,
                releaseBranchName,
                String.format("Additional commit for %s release", releaseNumber),
                String.format("Additional commit for %s release", releaseNumber)
        );

        bitbucketClient.mergePullRequest(repository, pr);
        bitbucketClient.deleteBranch(repository, additionalCommitBranchName);
        log.info("ADDITIONAL COMMIT WITH MESSAGE \"{}\" ADDED FOR RELEASE | pullRequestId={} url={}", commitMessage, pr.id(), pr.url());
    }
}