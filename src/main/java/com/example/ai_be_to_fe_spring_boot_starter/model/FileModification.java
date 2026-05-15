package com.example.ai_be_to_fe_spring_boot_starter.model;

/**
 * Represents a single file the AI wants to create, update, or delete
 * in the frontend repository.
 *
 * @param operation what to do with the file (CREATE / UPDATE / DELETE)
 * @param path      relative path inside the frontend repo (e.g. src/api/types/user.types.ts)
 * @param content   full file content — always the complete file, never a patch
 */
public record FileModification(
        OperationType operation,
        String path,
        String content
) {}

