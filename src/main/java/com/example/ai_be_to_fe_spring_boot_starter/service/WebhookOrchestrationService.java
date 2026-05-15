package com.example.ai_be_to_fe_spring_boot_starter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;

/**
 * Async wrapper around {@link FePipelineService} for the GitHub webhook flow.
 * <p>
 * Returns immediately so the webhook endpoint can respond with HTTP 202 without
 * waiting for the (potentially long) AI + GitHub operations to complete.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class WebhookOrchestrationService {

    private final FePipelineService fePipelineService;

    /**
     * Fires the full FE-generation pipeline asynchronously for the given commit SHA.
     *
     * @param commitSha the SHA of the backend commit that triggered the webhook
     */
    @Async
    public void process(String commitSha) {
        log.info("▶ Webhook triggered pipeline for commit {}", commitSha);
        try {
            var result = fePipelineService.run(commitSha, null);
            log.info("✔ Webhook pipeline finished – status={} summary={}",
                    result.status(), result.summary());
        } catch (Exception e) {
            log.error("✘ Webhook pipeline failed for commit {}", commitSha, e);
        }
    }
}
