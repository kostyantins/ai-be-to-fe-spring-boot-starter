package com.example.ai_be_to_fe_spring_boot_starter.model;

public enum GenerationStatus {
    /** At least one file was generated and a PR was opened in the frontend repo. */
    SUCCESS,
    /** The AI determined no frontend changes are needed for the given input. */
    NO_CHANGES,
    /** An error occurred during the pipeline. */
    ERROR
}

