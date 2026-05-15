package com.example.ai_be_to_fe_spring_boot_starter.model;

import java.util.List;

/**
 * Response body returned by {@code POST /api/fe-generator/generate}.
 *
 * @param status         outcome of the pipeline run
 * @param summary        human-readable description from the AI (or error message)
 * @param pullRequestUrl URL of the opened PR – present only when {@code status == SUCCESS}
 * @param branchName     branch created in the frontend repo – present only when {@code status == SUCCESS}
 * @param filesChanged   number of files the AI generated/updated/deleted
 * @param files          list of individual file operations performed
 */
public record OnDemandGenerationResponse(
        GenerationStatus status,
        String summary,
        String pullRequestUrl,
        String branchName,
        int filesChanged,
        List<FileModification> files
) {

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static OnDemandGenerationResponse success(String prUrl,
                                                     String branch,
                                                     AiFrontendResponse ai) {
        return new OnDemandGenerationResponse(
                GenerationStatus.SUCCESS,
                ai.summary(),
                prUrl,
                branch,
                ai.files().size(),
                ai.files()
        );
    }

    public static OnDemandGenerationResponse noChanges(String summary) {
        return new OnDemandGenerationResponse(
                GenerationStatus.NO_CHANGES,
                summary,
                null,
                null,
                0,
                List.of()
        );
    }

    public static OnDemandGenerationResponse error(String message) {
        return new OnDemandGenerationResponse(
                GenerationStatus.ERROR,
                message,
                null,
                null,
                0,
                List.of()
        );
    }
}

