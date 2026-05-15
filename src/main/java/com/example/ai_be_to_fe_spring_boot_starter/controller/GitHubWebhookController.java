package com.example.ai_be_to_fe_spring_boot_starter.controller;

import com.example.ai_be_to_fe_spring_boot_starter.config.AiFeGeneratorProperties;
import com.example.ai_be_to_fe_spring_boot_starter.model.PullRequestEventPayload;
import com.example.ai_be_to_fe_spring_boot_starter.model.PushEventPayload;
import com.example.ai_be_to_fe_spring_boot_starter.service.WebhookOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Receives GitHub webhook POST requests at {@code /github/webhook}.
 *
 * <h2>Security</h2>
 * Every request is validated against the {@code X-Hub-Signature-256} header using
 * HMAC-SHA256 with the configured {@code ai.fe-generator.webhook-secret}.
 * Requests that fail validation are rejected with HTTP 401.
 *
 * <h2>Supported events</h2>
 * <ul>
 *   <li>{@code push} – triggers when commits are pushed to the configured default branch</li>
 *   <li>{@code pull_request} – triggers when a PR targeting the default branch is merged</li>
 * </ul>
 *
 * The controller returns HTTP 202 immediately and delegates execution to the
 * async {@link WebhookOrchestrationService}.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/github/webhook")
public class GitHubWebhookController {

    private static final String HDR_SIGNATURE = "X-Hub-Signature-256";
    private static final String HDR_EVENT     = "X-GitHub-Event";

    private final WebhookOrchestrationService orchestrationService;
    private final AiFeGeneratorProperties     properties;
    private final ObjectMapper                objectMapper;

    @PostMapping
    public ResponseEntity<String> receive(
            @RequestHeader(HDR_SIGNATURE) String signature,
            @RequestHeader(HDR_EVENT)     String eventType,
            @RequestBody                  String rawBody) {

        // ── 1. Signature verification ────────────────────────────────────────
        if (!isValidSignature(rawBody, signature)) {
            log.warn("Invalid webhook signature – request rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid signature");
        }

        log.debug("Received GitHub event '{}' (signature OK)", eventType);

        // ── 2. Event routing ─────────────────────────────────────────────────
        try {
            switch (eventType) {
                case "push"         -> handlePush(rawBody);
                case "pull_request" -> handlePullRequest(rawBody);
                default             -> log.debug("Ignoring unsupported event type '{}'", eventType);
            }
        } catch (Exception e) {
            log.error("Error processing webhook event '{}'", eventType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing event");
        }

        return ResponseEntity.accepted().body("Accepted");
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    private void handlePush(String rawBody) throws Exception {
        PushEventPayload payload = objectMapper.readValue(rawBody, PushEventPayload.class);

        String targetRef = "refs/heads/" + properties.getBackendDefaultBranch();
        if (!targetRef.equals(payload.ref())) {
            log.debug("Push to '{}' ignored – only tracking '{}'", payload.ref(), targetRef);
            return;
        }

        String commitSha = payload.after();
        if (commitSha == null || commitSha.isBlank() || commitSha.matches("0+")) {
            log.debug("Push event has no valid commit SHA – skipping");
            return;
        }

        log.info("Push to '{}' detected – commit SHA: {}", properties.getBackendDefaultBranch(), commitSha);
        orchestrationService.process(commitSha);
    }

    private void handlePullRequest(String rawBody) throws Exception {
        PullRequestEventPayload payload = objectMapper.readValue(rawBody, PullRequestEventPayload.class);

        boolean isMergedToDefault = "closed".equals(payload.action())
                && payload.pullRequest() != null
                && payload.pullRequest().merged()
                && properties.getBackendDefaultBranch()
                        .equals(payload.pullRequest().base() != null
                                ? payload.pullRequest().base().ref() : "");

        if (!isMergedToDefault) {
            log.debug("PR event ignored – not a merge to '{}'", properties.getBackendDefaultBranch());
            return;
        }

        String commitSha = payload.pullRequest().mergeCommitSha();
        log.info("PR merged into '{}' – merge commit SHA: {}",
                properties.getBackendDefaultBranch(), commitSha);
        orchestrationService.process(commitSha);
    }

    // ── HMAC-SHA256 helpers ──────────────────────────────────────────────────

    /**
     * Verifies the {@code X-Hub-Signature-256} header.
     * Uses a constant-time comparison to prevent timing attacks.
     */
    private boolean isValidSignature(String payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("No webhook secret configured – skipping signature validation (INSECURE)");
            return true;
        }
        String expected = "sha256=" + computeHmacSha256(payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private String computeHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}



