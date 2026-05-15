package com.example.ai_be_to_fe_spring_boot_starter.controller;

import com.example.ai_be_to_fe_spring_boot_starter.model.OnDemandGenerationRequest;
import com.example.ai_be_to_fe_spring_boot_starter.model.OnDemandGenerationResponse;
import com.example.ai_be_to_fe_spring_boot_starter.service.FePipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand FE-generation endpoint.
 *
 * <pre>POST /api/fe-generator/generate</pre>
 *
 * <p>Unlike the GitHub webhook route this endpoint is <strong>synchronous</strong>: it
 * waits for the entire AI + GitHub pipeline to complete and returns the result
 * (including the PR URL) in the response body.</p>
 *
 * <h2>Request body</h2>
 * <pre>{@code
 * {
 *   "commitSha": "abc12345",          // optional – backend commit SHA to base generation on
 *   "prompt":    "Add user endpoint"  // optional – extra instructions / standalone description
 * }
 * }</pre>
 * At least one of the two fields must be non-blank; providing both is valid and
 * combines the commit diff with the additional instructions.
 *
 * <h2>Response – SUCCESS</h2>
 * <pre>{@code
 * HTTP 200
 * {
 *   "status": "SUCCESS",
 *   "summary": "Created UserDto type and RTK Query user API slice.",
 *   "pullRequestUrl": "https://github.com/org/my-react-app/pull/42",
 *   "branchName": "ai/fe-sync-abc12345",
 *   "filesChanged": 2,
 *   "files": [
 *     { "operation": "CREATE", "path": "src/api/types/user.types.ts", "content": "..." },
 *     { "operation": "CREATE", "path": "src/api/services/user.api.ts", "content": "..." }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Response – NO_CHANGES</h2>
 * <pre>{@code
 * HTTP 200
 * { "status": "NO_CHANGES", "summary": "No frontend changes are required.", "filesChanged": 0, "files": [] }
 * }</pre>
 *
 * <h2>Response – Error</h2>
 * <pre>{@code
 * HTTP 400  { "status": "ERROR", "summary": "At least one of 'commitSha' or 'prompt' must be provided." }
 * HTTP 500  { "status": "ERROR", "summary": "<error detail>" }
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/fe-generator")
public class OnDemandGenerationController {

    private final FePipelineService fePipelineService;

    @PostMapping("/generate")
    public ResponseEntity<OnDemandGenerationResponse> generate(
            @RequestBody OnDemandGenerationRequest request) {

        // ── Validate ──────────────────────────────────────────────────────────
        boolean hasCommit = request.commitSha() != null && !request.commitSha().isBlank();
        boolean hasPrompt = request.prompt()    != null && !request.prompt().isBlank();

        if (!hasCommit && !hasPrompt) {
            return ResponseEntity.badRequest().body(
                    OnDemandGenerationResponse.error(
                            "At least one of 'commitSha' or 'prompt' must be provided."));
        }

        log.info("On-demand generation request  commitSha={} hasPrompt={}",
                hasCommit ? request.commitSha() : "—", hasPrompt);

        // ── Run pipeline (synchronous) ────────────────────────────────────────
        try {
            OnDemandGenerationResponse result =
                    fePipelineService.run(request.commitSha(), request.prompt());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("On-demand generation failed", e);
            return ResponseEntity.internalServerError()
                    .body(OnDemandGenerationResponse.error(
                            "Pipeline error: " + e.getMessage()));
        }
    }
}

