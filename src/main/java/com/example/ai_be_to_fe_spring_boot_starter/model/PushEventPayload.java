package com.example.ai_be_to_fe_spring_boot_starter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal representation of a GitHub <em>push</em> webhook payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushEventPayload(

        /** e.g. "refs/heads/main" */
        String ref,

        /** SHA of the newest commit after the push */
        String after,

        @JsonProperty("head_commit")
        HeadCommit headCommit,

        Repository repository

) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeadCommit(String id, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(@JsonProperty("full_name") String fullName) {}
}

