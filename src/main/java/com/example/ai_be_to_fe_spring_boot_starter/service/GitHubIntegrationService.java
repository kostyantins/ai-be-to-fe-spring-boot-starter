package com.example.ai_be_to_fe_spring_boot_starter.service;

import com.example.ai_be_to_fe_spring_boot_starter.config.AiFeGeneratorProperties;
import com.example.ai_be_to_fe_spring_boot_starter.model.FileModification;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Encapsulates every interaction with the GitHub REST API:
 * <ul>
 *   <li>Reading commit diffs from the backend repository</li>
 *   <li>Reading existing frontend files (used by the AI tool)</li>
 *   <li>Creating branches, committing files and opening Pull Requests in the frontend repository</li>
 * </ul>
 */
@Slf4j
public class GitHubIntegrationService {

    private static final String FE_TEMPLATE_FILE = ".ai-fe-template.txt";

    private final GitHub github;
    private final AiFeGeneratorProperties properties;

    public GitHubIntegrationService(AiFeGeneratorProperties properties) {
        this.properties = properties;
        try {
            this.github = new GitHubBuilder()
                    .withOAuthToken(properties.getGithubToken())
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialise GitHub client – check ai.fe-generator.github-token", e);
        }
    }

    // ── Backend ─────────────────────────────────────────────────────────────

    /**
     * Assembles a human-readable diff string for the given commit SHA from the
     * configured backend repository.  The string is passed verbatim to the LLM.
     */
    public String fetchCommitDiff(String commitSha) throws IOException {
        GHRepository repo = github.getRepository(properties.getBackendRepoName());
        GHCommit commit = repo.getCommit(commitSha);

        StringBuilder diff = new StringBuilder();
        diff.append("Commit SHA : ").append(commitSha).append("\n");
        diff.append("Message    : ").append(commit.getCommitShortInfo().getMessage()).append("\n\n");

        for (GHCommit.File file : commit.listFiles()) {
            diff.append("──────────────────────────────────────\n");
            diff.append("File   : ").append(file.getFileName()).append("\n");
            diff.append("Status : ").append(file.getStatus()).append("\n");
            if (file.getPatch() != null && !file.getPatch().isBlank()) {
                diff.append("Patch  :\n").append(file.getPatch()).append("\n");
            }
        }
        return diff.toString();
    }

    // ── Frontend – read ──────────────────────────────────────────────────────

    /**
     * Reads the raw content of a file from the <em>default branch</em> of the
     * frontend repository.  Used by {@link com.example.ai_be_to_fe_spring_boot_starter.tools.GitHubFileTools}.
     *
     * @param filePath path relative to the repository root
     * @return file content, or {@link Optional#empty()} when the file does not exist
     */
    public Optional<String> fetchFileContent(String filePath) {
        return fetchFileContent(filePath, null); // null → default branch
    }

    /**
     * Reads the raw content of a file from a specific branch of the frontend repository.
     */
    public Optional<String> fetchFileContent(String filePath, String branch) {
        try {
            GHRepository repo = github.getRepository(properties.getFrontendRepoName());
            GHContent content = (branch == null)
                    ? repo.getFileContent(filePath)
                    : repo.getFileContent(filePath, branch);
            try (InputStream is = content.read()) {
                return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (GHFileNotFoundException e) {
            return Optional.empty();
        } catch (IOException e) {
            log.error("Error fetching frontend file '{}' from branch '{}'", filePath, branch, e);
            return Optional.empty();
        }
    }

    /**
     * Looks for an {@code .ai-fe-template.txt} file at the root of the frontend
     * repository.  When present its content is injected into the AI prompt so
     * the LLM understands the project's specific conventions (e.g. RTK Query, Axios …).
     */
    public Optional<String> fetchFETemplate() {
        log.debug("Looking for {} in frontend repo", FE_TEMPLATE_FILE);
        return fetchFileContent(FE_TEMPLATE_FILE);
    }

    // ── Frontend – write ─────────────────────────────────────────────────────

    /**
     * Creates a new branch in the frontend repository branching off the default branch.
     *
     * @param branchName name of the new branch (e.g. {@code ai/fe-sync-abc12345})
     * @return the branch name
     */
    public String createBranch(String branchName) throws IOException {
        GHRepository repo = github.getRepository(properties.getFrontendRepoName());
        String defaultBranch = repo.getDefaultBranch();
        String headSha = repo.getBranch(defaultBranch).getSHA1();
        repo.createRef("refs/heads/" + branchName, headSha);
        log.info("Created branch '{}' in {}", branchName, properties.getFrontendRepoName());
        return branchName;
    }

    /**
     * Iterates the AI-generated file list and performs CREATE / UPDATE / DELETE
     * operations via the GitHub Contents API.
     * <p>
     * For UPDATE the method always writes the <em>complete</em> file supplied by the
     * AI.  If the file does not exist in the branch it falls back to CREATE.
     */
    public void commitFiles(String branchName, List<FileModification> files) throws IOException {
        GHRepository repo = github.getRepository(properties.getFrontendRepoName());

        for (FileModification file : files) {
            log.debug("  {} {}", file.operation(), file.path());
            switch (file.operation()) {
                case CREATE -> createFile(repo, branchName, file);
                case UPDATE -> updateOrCreateFile(repo, branchName, file);
                case DELETE -> deleteFile(repo, branchName, file);
            }
        }
    }

    /**
     * Opens a Pull Request in the frontend repository from {@code branchName} into the default branch.
     *
     * @return the created {@link GHPullRequest}
     */
    public GHPullRequest createPullRequest(String branchName, String title, String body) throws IOException {
        GHRepository repo = github.getRepository(properties.getFrontendRepoName());
        String defaultBranch = repo.getDefaultBranch();
        GHPullRequest pr = repo.createPullRequest(title, branchName, defaultBranch, body);
        log.info("Pull Request created: {}", pr.getHtmlUrl());
        return pr;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void createFile(GHRepository repo, String branch, FileModification file) throws IOException {
        repo.createContent()
                .path(file.path())
                .content(file.content().getBytes(StandardCharsets.UTF_8))
                .branch(branch)
                .message("feat(ai-sync): add " + file.path())
                .commit();
    }

    private void updateOrCreateFile(GHRepository repo, String branch, FileModification file) throws IOException {
        try {
            GHContent existing = repo.getFileContent(file.path(), branch);
            existing.update(
                    file.content().getBytes(StandardCharsets.UTF_8),
                    "feat(ai-sync): update " + file.path(),
                    branch
            );
        } catch (GHFileNotFoundException e) {
            // File doesn't exist on this branch yet – create it instead
            log.debug("File {} not found on branch '{}', creating it", file.path(), branch);
            createFile(repo, branch, file);
        }
    }

    private void deleteFile(GHRepository repo, String branch, FileModification file) throws IOException {
        try {
            GHContent existing = repo.getFileContent(file.path(), branch);
            existing.delete("chore(ai-sync): remove " + file.path(), branch);
        } catch (GHFileNotFoundException e) {
            log.warn("Tried to delete {} but it was not found on branch '{}'", file.path(), branch);
        }
    }
}




