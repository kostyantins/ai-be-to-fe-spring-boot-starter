package com.example.ai_be_to_fe_spring_boot_starter;

import com.example.ai_be_to_fe_spring_boot_starter.config.AiFeGeneratorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight smoke-tests that do NOT require a real OpenAI key or GitHub token.
 * The full integration context is covered by integration tests (run separately).
 */
class AiBeToFeSpringBootStarterApplicationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultPropertiesAreApplied() {
        contextRunner.run(ctx -> {
            AiFeGeneratorProperties props = ctx.getBean(AiFeGeneratorProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getBackendDefaultBranch()).isEqualTo("main");
            assertThat(props.getFrontendSourcePath()).isEqualTo("src/api");
            assertThat(props.getAiSystemPrompt()).isNotBlank();
        });
    }

    @Test
    void starterCanBeDisabledViaProperty() {
        contextRunner
                .withPropertyValues("ai.fe-generator.enabled=false")
                .run(ctx -> {
                    AiFeGeneratorProperties props = ctx.getBean(AiFeGeneratorProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                });
    }

    @Test
    void customPropertiesAreBindedCorrectly() {
        contextRunner
                .withPropertyValues(
                        "ai.fe-generator.github-token=ghp_test",
                        "ai.fe-generator.backend-repo-name=org/be",
                        "ai.fe-generator.frontend-repo-name=org/fe",
                        "ai.fe-generator.backend-default-branch=develop"
                )
                .run(ctx -> {
                    AiFeGeneratorProperties props = ctx.getBean(AiFeGeneratorProperties.class);
                    assertThat(props.getGithubToken()).isEqualTo("ghp_test");
                    assertThat(props.getBackendRepoName()).isEqualTo("org/be");
                    assertThat(props.getFrontendRepoName()).isEqualTo("org/fe");
                    assertThat(props.getBackendDefaultBranch()).isEqualTo("develop");
                });
    }

    // Minimal config to bind properties without starting the full Spring context
    @EnableConfigurationProperties(AiFeGeneratorProperties.class)
    static class TestConfig {}
}
