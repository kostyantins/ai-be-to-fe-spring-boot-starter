package com.example.ai_be_to_fe_spring_boot_starter.service;

import com.example.ai_be_to_fe_spring_boot_starter.config.AiFeGeneratorProperties;
import com.example.ai_be_to_fe_spring_boot_starter.model.AiFrontendResponse;
import com.example.ai_be_to_fe_spring_boot_starter.tools.GitHubFileTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * Sends the backend commit diff to the configured LLM and parses a structured
 * {@link AiFrontendResponse} from the reply.
 *
 * <h2>Agentic flow</h2>
 * The request is equipped with a {@link GitHubFileTools} tool so that the model
 * can call {@code fetchFrontendFile(path)} during its reasoning loop to inspect
 * existing frontend files before deciding how to modify them.  This avoids the
 * need for a manual two-pass strategy in most cases.
 *
 * <h2>Structured output</h2>
 * A {@link BeanOutputConverter} injects the JSON schema of {@link AiFrontendResponse}
 * directly into the prompt so the model knows exactly what format to return.
 * The raw response is sanitised (markdown fences stripped) before parsing.
 */
@Slf4j
public class AiCodeGeneratorService {

    private final ChatClient chatClient;
    private final AiFeGeneratorProperties properties;
    private final GitHubIntegrationService gitHubIntegrationService;

    public AiCodeGeneratorService(ChatClient.Builder chatClientBuilder,
                                  AiFeGeneratorProperties properties,
                                  GitHubIntegrationService gitHubIntegrationService) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.gitHubIntegrationService = gitHubIntegrationService;
    }

    /**
     * Convenience overload used by the GitHub webhook path where no extra user prompt
     * is supplied – delegates to the full three-argument variant.
     */
    public AiFrontendResponse generateFrontendCode(String backendDiff, String feTemplate) {
        return generateFrontendCode(backendDiff, null, feTemplate);
    }

    /**
     * Core generation method.
     *
     * @param backendDiff   diff text assembled from a commit (may be {@code null} when
     *                      the caller only has a free-text prompt)
     * @param userPrompt    free-text instructions or additional context supplied by the
     *                      caller (may be {@code null} when only a diff is available)
     * @param feTemplate    optional content of {@code .ai-fe-template.txt}
     * @return parsed AI response; {@code files} list may be empty if no FE changes are needed
     */
    public AiFrontendResponse generateFrontendCode(String backendDiff,
                                                   String userPrompt,
                                                   String feTemplate) {
        BeanOutputConverter<AiFrontendResponse> converter =
                new BeanOutputConverter<>(AiFrontendResponse.class);

        String systemPrompt = buildSystemPrompt(feTemplate);
        String userMsg      = buildUserPrompt(backendDiff, userPrompt, converter.getFormat());

        log.debug("Calling AI for frontend code generation …");

        String rawContent = chatClient.prompt()
                .system(systemPrompt)
                .user(userMsg)
                .tools(new GitHubFileTools(gitHubIntegrationService))
                .call()
                .content();

        String sanitised = sanitise(rawContent);
        log.debug("AI raw response (sanitised):\n{}", sanitised);

        return converter.convert(sanitised);
    }

    // ── Prompt builders ──────────────────────────────────────────────────────

    private String buildSystemPrompt(String feTemplate) {
        String templateSection = (feTemplate != null && !feTemplate.isBlank())
                ? """

                  ── Frontend project conventions (.ai-fe-template.txt) ──────────────────
                  %s
                  ────────────────────────────────────────────────────────────────────────
                  """.formatted(feTemplate)
                : "\nNo custom frontend template found – use sensible RTK Query defaults.\n";

        return """
                %s
                %s
                Tool usage guidance
                ───────────────────
                You have access to the tool `fetchFrontendFile(filePath)`.
                Before generating an UPDATE for any file, call this tool to read its current
                content so you can preserve existing imports and exports.
                If the tool returns FILE_NOT_FOUND, use the CREATE operation instead.

                Important rules
                ───────────────
                • Always return the COMPLETE file content – never diffs or partial snippets.
                • File paths must be relative to the repository root.
                • If the backend change requires no frontend changes whatsoever, return an
                  empty `files` array and explain why in the `summary` field.
                """.formatted(properties.getAiSystemPrompt(), templateSection);
    }

    private String buildUserPrompt(String backendDiff, String userPrompt, String jsonSchema) {
        StringBuilder context = new StringBuilder();

        if (backendDiff != null && !backendDiff.isBlank()) {
            context.append("── Backend Commit Diff ──────────────────────────────────────────────────\n");
            context.append(backendDiff);
            context.append("\n────────────────────────────────────────────────────────────────────────\n\n");
        }

        if (userPrompt != null && !userPrompt.isBlank()) {
            context.append("── Additional Instructions / Context ────────────────────────────────────\n");
            context.append(userPrompt);
            context.append("\n────────────────────────────────────────────────────────────────────────\n\n");
        }

        return """
                Analyse the following context and generate the corresponding
                React / TypeScript frontend code (types, RTK Query API slices, hooks, etc.).

                %s
                Return your answer as a single JSON object that conforms EXACTLY to this schema:
                %s
                """.formatted(context.toString(), jsonSchema);
    }

    // ── Response sanitisation ────────────────────────────────────────────────

    /**
     * Strips optional markdown code fences that some models wrap around their JSON.
     * Handles both {@code ```json ... ```} and plain {@code ``` ... ```} variants.
     */
    private String sanitise(String raw) {
        if (raw == null) return "{}";
        // Remove leading/trailing whitespace first
        String trimmed = raw.strip();
        // Strip ```json ... ``` or ``` ... ```
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence).strip();
            }
        }
        return trimmed;
    }
}



