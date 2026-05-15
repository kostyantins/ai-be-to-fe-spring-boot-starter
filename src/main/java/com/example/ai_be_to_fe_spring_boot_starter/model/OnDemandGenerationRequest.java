package com.example.ai_be_to_fe_spring_boot_starter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request body for the on-demand FE generation endpoint
 * {@code POST /api/fe-generator/generate}.
 *
 * <p>At least one of {@code commitSha} or {@code prompt} must be non-blank.</p>
 *
 * <h3>Scenarios</h3>
 * <ul>
 *   <li><b>Commit only</b> – the starter fetches the diff for that SHA, sends it to the
 *       AI and generates the corresponding TypeScript code.</li>
 *   <li><b>Prompt only</b> – the AI generates frontend code based purely on the
 *       free-text instructions (useful when you want to describe a change in natural
 *       language without an actual commit).</li>
 *   <li><b>Both</b> – the diff provides the structural context while the prompt adds
 *       extra instructions (e.g. "also add a loading skeleton component").</li>
 * </ul>
 *
 * @param commitSha  optional GitHub commit SHA from the backend repository
 * @param prompt     optional free-text instructions / additional context for the AI
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnDemandGenerationRequest(
        String commitSha,
        String prompt
) {}

