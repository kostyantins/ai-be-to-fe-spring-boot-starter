package com.example.ai_be_to_fe_spring_boot_starter.service;

import com.example.ai_be_to_fe_spring_boot_starter.model.AiFrontendResponse;
import com.example.ai_be_to_fe_spring_boot_starter.model.FileModification;
import com.example.ai_be_to_fe_spring_boot_starter.model.OnDemandGenerationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHPullRequest;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Synchronous FE-generation pipeline shared by both trigger paths:
 * <ul>
 *   <li>{@link WebhookOrchestrationService} – invokes this via {@code @Async} for
 *       the GitHub webhook flow (fire-and-forget)</li>
 *   <li>{@link com.example.ai_be_to_fe_spring_boot_starter.controller.OnDemandGenerationController}
 *       – invokes this directly and returns the result to the caller</li>
 * </ul>
 *
 * <h2>Input rules</h2>
 * <ul>
 *   <li><b>commitSha only</b> – diff is fetched from GitHub; prompt is ignored.</li>
 *   <li><b>prompt only</b> – the AI works purely from the free-text instructions.</li>
 *   <li><b>both</b> – the diff provides structural context; the prompt adds extra
 *       instructions on top of it.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class FePipelineService {

    private final GitHubIntegrationService gitHubIntegrationService;
    private final AiCodeGeneratorService   aiCodeGeneratorService;

    /**
     * Runs the full pipeline and returns a structured result.
     *
     * @param commitSha  optional backend commit SHA
     * @param userPrompt optional free-text instructions
     * @return {@link OnDemandGenerationResponse} – never {@code null}
     */
    public OnDemandGenerationResponse run(String commitSha, String userPrompt) throws IOException {
        boolean hasCommit = commitSha != null && !commitSha.isBlank();
        boolean hasPrompt = userPrompt != null && !userPrompt.isBlank();

        // ── 1. Fetch diff (when a commit SHA is supplied) ─────────────────────
        String diff = null;
        if (hasCommit) {
            diff = gitHubIntegrationService.fetchCommitDiff(commitSha);
            log.debug("Fetched BE diff for commit {} ({} chars)", commitSha, diff.length());
        }

        // ── 2. Load optional .ai-fe-template.txt ─────────────────────────────
        String feTemplate = gitHubIntegrationService.fetchFETemplate().orElse(null);
        if (feTemplate != null) {
            log.debug("Loaded .ai-fe-template.txt ({} chars)", feTemplate.length());
        }

        // ── 3. Call the AI ────────────────────────────────────────────────────
        log.info("▶ Calling AI  commitSha={} hasPrompt={}", hasCommit ? commitSha : "—", hasPrompt);
        AiFrontendResponse aiResponse = aiCodeGeneratorService.generateFrontendCode(diff, userPrompt, feTemplate);

        // ── 4. No-changes guard ───────────────────────────────────────────────
        if (aiResponse == null || aiResponse.files() == null || aiResponse.files().isEmpty()) {
            String summary = aiResponse != null ? aiResponse.summary() : "AI returned no response.";
            log.info("✔ No FE changes required. Summary: {}", summary);
            return OnDemandGenerationResponse.noChanges(summary);
        }

        log.info("AI generated {} file operation(s): {}",
                aiResponse.files().size(),
                aiResponse.files().stream()
                        .map(f -> f.operation() + " " + f.path())
                        .collect(Collectors.joining(", ")));

        // ── 5. Determine branch name ──────────────────────────────────────────
        String branchSuffix = hasCommit
                ? commitSha.substring(0, Math.min(8, commitSha.length()))
                : String.valueOf(System.currentTimeMillis()).substring(5); // last 8 digits of millis

        String branchName = hasCommit
                ? "ai/fe-sync-" + branchSuffix
                : "ai/fe-on-demand-" + branchSuffix;

        // ── 6. Create branch ──────────────────────────────────────────────────
        gitHubIntegrationService.createBranch(branchName);

        // ── 7. Commit files ───────────────────────────────────────────────────
        gitHubIntegrationService.commitFiles(branchName, aiResponse.files());

        // ── 8. Open Pull Request ──────────────────────────────────────────────
        String prTitle = hasCommit
                ? "AI FE Sync – " + branchSuffix
                : "AI FE Generation – " + branchSuffix;

        String prBody = buildPrBody(aiResponse, commitSha, userPrompt);
        GHPullRequest pr = gitHubIntegrationService.createPullRequest(branchName, prTitle, prBody);

        log.info("✔ Pipeline complete. PR: {}", pr.getHtmlUrl());
        return OnDemandGenerationResponse.success(pr.getHtmlUrl().toString(), branchName, aiResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildPrBody(AiFrontendResponse response, String commitSha, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 AI-Generated Frontend Sync\n\n");

        if (commitSha != null && !commitSha.isBlank()) {
            sb.append("**Triggered by backend commit:** `").append(commitSha).append("`\n\n");
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("**User prompt:**\n> ").append(userPrompt.replace("\n", "\n> ")).append("\n\n");
        }

        sb.append("**Summary:** ").append(response.summary()).append("\n\n");
        sb.append("### Changed files\n\n");
        sb.append("| Operation | Path |\n");
        sb.append("|-----------|------|\n");
        response.files().forEach((FileModification f) ->
                sb.append("| `").append(f.operation()).append("` | `").append(f.path()).append("` |\n"));
        sb.append("\n---\n");
        sb.append("_Generated by [ai-be-to-fe-spring-boot-starter](https://github.com/). "
                + "Please review carefully before merging._\n");
        return sb.toString();
    }
}

