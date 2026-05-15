package com.example.ai_be_to_fe_spring_boot_starter.model;

import java.util.List;

/**
 * Structured JSON response produced by the LLM.
 *
 * @param summary human-readable description of what was generated
 * @param files   list of file operations; empty means no FE changes required
 */
public record AiFrontendResponse(
        String summary,
        List<FileModification> files
) {}

