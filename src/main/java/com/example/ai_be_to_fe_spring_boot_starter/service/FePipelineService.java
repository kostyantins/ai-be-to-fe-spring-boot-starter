package com.example.ai_be_to_fe_spring_boot_starter.service;

import com.example.ai_be_to_fe_spring_boot_starter.model.AiFrontendResponse;
import com.example.ai_be_to_fe_spring_boot_starter.model.FileModification;
import com.example.ai_be_to_fe_spring_boot_starter.model.OnDemandGenerationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHPullRequest;
import org.springframework.util.StopWatch;

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

        StopWatch sw = new StopWatch("fe-pipeline");
        log.info("╔══ FE Pipeline START  commitSha={}  hasPrompt={} ══╗",
                hasCommit ? commitSha.substring(0, Math.min(8, commitSha.length())) : "—", hasPrompt);

        // ── 1. Fetch diff (when a commit SHA is supplied) ─────────────────────
        String diff = null;
        if (hasCommit) {
            sw.start("fetch-diff");
            diff = gitHubIntegrationService.fetchCommitDiff(commitSha);
            sw.stop();
            log.info("  [1/6] Diff fetched  chars={}  elapsed={}ms",
                    diff.length(), sw.lastTaskInfo().getTimeMillis());
        }

        // ── 2. Load optional .ai-fe-template.txt ─────────────────────────────
        String feTemplate = gitHubIntegrationService.fetchFETemplate().orElse(null);
        if (feTemplate != null) {
            log.info("  [2/6] .ai-fe-template.txt loaded  chars={}", feTemplate.length());
        } else {
            log.info("  [2/6] .ai-fe-template.txt not found – using default prompt");
        }

        // ── 3. Call the AI ────────────────────────────────────────────────────
        log.info("  [3/6] Calling AI model…");
        sw.start("ai-call");
        AiFrontendResponse aiResponse = aiCodeGeneratorService.generateFrontendCode(diff, userPrompt, feTemplate);
        sw.stop();
        log.info("  [3/6] AI responded  elapsed={}ms", sw.lastTaskInfo().getTimeMillis());

        // ── 4. No-changes guard ───────────────────────────────────────────────
        if (aiResponse == null || aiResponse.files() == null || aiResponse.files().isEmpty()) {
            String summary = aiResponse != null ? aiResponse.summary() : "AI returned no response.";
            log.info("╚══ FE Pipeline END  status=NO_CHANGES  summary='{}' ══╝", summary);
            return OnDemandGenerationResponse.noChanges(summary);
        }

        log.info("  [4/6] AI generated {} file operation(s): {}",
                aiResponse.files().size(),
                aiResponse.files().stream()
                        .map(f -> f.operation() + " " + f.path())
                        .collect(Collectors.joining(", ")));

        // ── 5. Determine branch name ──────────────────────────────────────────
        String branchSuffix = hasCommit
                ? commitSha.substring(0, Math.min(8, commitSha.length()))
                : String.valueOf(System.currentTimeMillis()).substring(5);

        String branchName = hasCommit
                ? "ai/fe-sync-" + branchSuffix
                : "ai/fe-on-demand-" + branchSuffix;

        // ── 6. Create branch ──────────────────────────────────────────────────
        sw.start("create-branch");
        gitHubIntegrationService.createBranch(branchName);
        sw.stop();
        log.info("  [5/6] Branch '{}' created  elapsed={}ms", branchName, sw.lastTaskInfo().getTimeMillis());

        // ── 7. Commit files ───────────────────────────────────────────────────
        sw.start("commit-files");
        gitHubIntegrationService.commitFiles(branchName, aiResponse.files());
        sw.stop();
        log.info("  [5/6] Files committed  elapsed={}ms", sw.lastTaskInfo().getTimeMillis());

        // ── 8. Open Pull Request ──────────────────────────────────────────────
        String prTitle = hasCommit
                ? "AI FE Sync – " + branchSuffix
                : "AI FE Generation – " + branchSuffix;

        sw.start("create-pr");
        String prBody = buildPrBody(aiResponse, commitSha, userPrompt);
        GHPullRequest pr = gitHubIntegrationService.createPullRequest(branchName, prTitle, prBody);
        sw.stop();

        long totalMs = sw.getTotalTimeMillis();
        log.info("╚══ FE Pipeline END  status=SUCCESS  files={}  totalTime={}ms ══╝", aiResponse.files().size(), totalMs);
        log.info("    PR: {}", pr.getHtmlUrl());

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

