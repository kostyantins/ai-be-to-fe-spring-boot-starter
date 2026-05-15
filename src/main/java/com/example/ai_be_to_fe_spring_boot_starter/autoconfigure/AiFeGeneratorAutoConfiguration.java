package com.example.ai_be_to_fe_spring_boot_starter.autoconfigure;

import com.example.ai_be_to_fe_spring_boot_starter.config.AiFeGeneratorProperties;
import com.example.ai_be_to_fe_spring_boot_starter.controller.GitHubWebhookController;
import com.example.ai_be_to_fe_spring_boot_starter.controller.OnDemandGenerationController;
import com.example.ai_be_to_fe_spring_boot_starter.service.AiCodeGeneratorService;
import com.example.ai_be_to_fe_spring_boot_starter.service.FePipelineService;
import com.example.ai_be_to_fe_spring_boot_starter.service.GitHubIntegrationService;
import com.example.ai_be_to_fe_spring_boot_starter.service.WebhookOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot auto-configuration for the AI BE-to-FE starter.
 *
 * <p>All beans are created when {@code ai.fe-generator.enabled=true} (the default).
 * Set it to {@code false} to completely disable the starter without removing the dependency.</p>
 *
 * <p>Register in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.</p>
 */
@AutoConfiguration
@EnableAsync
@EnableConfigurationProperties(AiFeGeneratorProperties.class)
@ConditionalOnProperty(
        prefix = "ai.fe-generator",
        name   = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AiFeGeneratorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GitHubIntegrationService gitHubIntegrationService(AiFeGeneratorProperties properties) {
        return new GitHubIntegrationService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AiCodeGeneratorService aiCodeGeneratorService(ChatClient.Builder builder,
                                                         AiFeGeneratorProperties properties,
                                                         GitHubIntegrationService gitHubIntegrationService) {
        return new AiCodeGeneratorService(builder, properties, gitHubIntegrationService);
    }

    @Bean
    @ConditionalOnMissingBean
    public FePipelineService fePipelineService(GitHubIntegrationService gitHubIntegrationService,
                                               AiCodeGeneratorService   aiCodeGeneratorService) {
        return new FePipelineService(gitHubIntegrationService, aiCodeGeneratorService);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebhookOrchestrationService webhookOrchestrationService(FePipelineService fePipelineService) {
        return new WebhookOrchestrationService(fePipelineService);
    }

    @Bean
    @ConditionalOnMissingBean
    public GitHubWebhookController gitHubWebhookController(
            WebhookOrchestrationService orchestrationService,
            AiFeGeneratorProperties     properties,
            ObjectMapper                objectMapper) {
        return new GitHubWebhookController(orchestrationService, properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public OnDemandGenerationController onDemandGenerationController(FePipelineService fePipelineService) {
        return new OnDemandGenerationController(fePipelineService);
    }
}
