package com.example.ai_be_to_fe_spring_boot_starter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal representation of a GitHub <em>pull_request</em> webhook payload.
 * We only care about merged PRs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestEventPayload(

        /** e.g. "closed" */
        String action,

        @JsonProperty("pull_request")
        PullRequest pullRequest,

        Repository repository

) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
            boolean merged,
            @JsonProperty("merge_commit_sha") String mergeCommitSha,
            PullRequestBase base
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequestBase(String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(@JsonProperty("full_name") String fullName) {}
}

