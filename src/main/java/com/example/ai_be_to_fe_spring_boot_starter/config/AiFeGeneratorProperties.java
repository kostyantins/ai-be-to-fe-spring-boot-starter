package com.example.ai_be_to_fe_spring_boot_starter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All externalisable settings for the AI BE-to-FE starter.
 * <p>
 * Example {@code application.yml} block:
 * <pre>
 * ai:
 *   fe-generator:
 *     enabled: true
 *     github-token: ghp_...
 *     webhook-secret: my-secret
 *     backend-repo-name: my-org/my-backend
 *     backend-default-branch: main
 *     frontend-repo-name: my-org/my-react-app
 *     frontend-source-path: src/api
 *     ai-system-prompt: "You are an expert React TypeScript developer..."
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai.fe-generator")
public class AiFeGeneratorProperties {

    /** Set to {@code false} to disable the entire starter without removing it. */
    private boolean enabled = true;

    /** GitHub personal-access-token (or fine-grained token) with repo + PR write permissions. */
    private String githubToken;

    /**
     * Secret used to verify the {@code X-Hub-Signature-256} header sent by GitHub.
     * Must match the value configured in the repository webhook settings.
     */
    private String webhookSecret;

    // ── Backend repository ──────────────────────────────────────────────────

    /** Full repository name, e.g. {@code my-org/my-backend}. */
    private String backendRepoName;

    /**
     * Branch that triggers the AI generation pipeline.
     * Pushes to other branches are silently ignored.
     */
    private String backendDefaultBranch = "main";

    // ── Frontend repository ─────────────────────────────────────────────────

    /** Full repository name, e.g. {@code my-org/my-react-app}. */
    private String frontendRepoName;

    /**
     * Root path inside the frontend repo where generated TypeScript files land.
     * The AI may create sub-directories inside this path.
     */
    private String frontendSourcePath = "src/api";

    // ── AI / prompt tuning ──────────────────────────────────────────────────

    /**
     * System-level instruction prepended to every AI prompt.
     * Override this to match the specific frameworks your FE team uses
     * (e.g. RTK Query, React Query, Axios, SWR …).
     */
    private String aiSystemPrompt = """
            You are an expert React TypeScript developer.
            You follow modern best practices including RTK Query for data fetching,
            strict TypeScript typing, and clean separation of concerns.
            When generating API service files you use RTK Query's createApi / fetchBaseQuery
            and export both the api slice and the generated hooks.
            When generating type files you export plain TypeScript interfaces or types.
            Always return the COMPLETE file content – never diffs or partial snippets.
            Always carfully test before commit.
            """;
}
