package com.example.ai_be_to_fe_spring_boot_starter.tools;

import com.example.ai_be_to_fe_spring_boot_starter.service.GitHubIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Spring AI tool class that the LLM can call during its reasoning loop to fetch
 * the existing content of a frontend file.  This enables the "agentic" two-pass
 * strategy: the AI first reads what already exists, then produces the final diff.
 */
@Slf4j
@RequiredArgsConstructor
public class GitHubFileTools {

    private final GitHubIntegrationService gitHubIntegrationService;

    /**
     * Called by the LLM to read an existing frontend file before deciding how to modify it.
     *
     * @param filePath relative path inside the frontend repo, e.g. {@code src/api/user.service.ts}
     * @return file content as a string, or a "not found" message so the LLM knows to CREATE it
     */
    @Tool(description = """
            Fetches the current content of a file from the frontend GitHub repository.
            Use this to read existing TypeScript/React files before modifying them so that
            you preserve imports, existing exports, and overall file structure.
            Returns the raw file content or a message indicating the file does not exist yet.
            """)
    public String fetchFrontendFile(String filePath) {
        log.debug("LLM tool call – fetching frontend file: {}", filePath);
        return gitHubIntegrationService
                .fetchFileContent(filePath)
                .orElse("FILE_NOT_FOUND: " + filePath + " does not exist yet – use CREATE operation.");
    }
}



