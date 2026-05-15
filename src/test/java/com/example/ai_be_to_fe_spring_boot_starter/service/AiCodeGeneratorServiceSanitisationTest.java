package com.example.ai_be_to_fe_spring_boot_starter.service;

import com.example.ai_be_to_fe_spring_boot_starter.config.AiFeGeneratorProperties;
import com.example.ai_be_to_fe_spring_boot_starter.model.AiFrontendResponse;
import com.example.ai_be_to_fe_spring_boot_starter.model.FileModification;
import com.example.ai_be_to_fe_spring_boot_starter.model.OperationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the JSON sanitisation logic without making any real AI or GitHub calls.
 */
class AiCodeGeneratorServiceSanitisationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Simulates what the sanitiser does: strips markdown fences then parses.
     */
    @Test
    void sanitisesMarkdownJsonFence() throws Exception {
        AiFrontendResponse expected = new AiFrontendResponse(
                "Added user types",
                List.of(new FileModification(OperationType.CREATE,
                        "src/api/types/user.types.ts",
                        "export interface UserDto { id: number; }"))
        );

        String rawJson   = objectMapper.writeValueAsString(expected);
        String withFence = "```json\n" + rawJson + "\n```";
        String sanitised = sanitise(withFence);

        BeanOutputConverter<AiFrontendResponse> converter =
                new BeanOutputConverter<>(AiFrontendResponse.class);
        AiFrontendResponse actual = converter.convert(sanitised);

        assertThat(actual.summary()).isEqualTo("Added user types");
        assertThat(actual.files()).hasSize(1);
        assertThat(actual.files().get(0).operation()).isEqualTo(OperationType.CREATE);
        assertThat(actual.files().get(0).path()).isEqualTo("src/api/types/user.types.ts");
    }

    @Test
    void sanitisesPlainFenceWithoutLanguageTag() throws Exception {
        String rawJson   = "{\"summary\":\"no changes\",\"files\":[]}";
        String withFence = "```\n" + rawJson + "\n```";
        String sanitised = sanitise(withFence);
        assertThat(sanitised).isEqualTo(rawJson);
    }

    @Test
    void passthroughWhenNoFencePresent() {
        String rawJson = "{\"summary\":\"clean\",\"files\":[]}";
        assertThat(sanitise(rawJson)).isEqualTo(rawJson);
    }

    // Duplicate of the private method in AiCodeGeneratorService for testing purposes
    private static String sanitise(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.strip();
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

