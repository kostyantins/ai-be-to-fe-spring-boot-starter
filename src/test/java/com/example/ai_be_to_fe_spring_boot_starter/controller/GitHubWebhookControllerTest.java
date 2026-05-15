package com.example.ai_be_to_fe_spring_boot_starter.controller;

import com.example.ai_be_to_fe_spring_boot_starter.config.AiFeGeneratorProperties;
import com.example.ai_be_to_fe_spring_boot_starter.service.WebhookOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookControllerTest {

    private static final String SECRET  = "test-webhook-secret";
    private static final String PAYLOAD = "{\"ref\":\"refs/heads/main\",\"after\":\"abc12345\","
            + "\"head_commit\":{\"id\":\"abc12345\",\"message\":\"test\"},"
            + "\"repository\":{\"full_name\":\"org/be\"}}";

    @Mock
    private WebhookOrchestrationService orchestrationService;

    private GitHubWebhookController controller;

    @BeforeEach
    void setUp() {
        AiFeGeneratorProperties props = new AiFeGeneratorProperties();
        props.setWebhookSecret(SECRET);
        props.setBackendDefaultBranch("main");
        controller = new GitHubWebhookController(orchestrationService, props, new ObjectMapper());
    }

    @Test
    void validSignatureAcceptsPushEvent() throws Exception {
        String sig = computeSignature(PAYLOAD, SECRET);
        ResponseEntity<String> response = controller.receive(sig, "push", PAYLOAD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(orchestrationService, times(1)).process("abc12345");
    }

    @Test
    void invalidSignatureReturns401() {
        ResponseEntity<String> response = controller.receive("sha256=bad", "push", PAYLOAD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void pushToNonDefaultBranchIsIgnored() throws Exception {
        String nonDefaultPayload = PAYLOAD.replace("refs/heads/main", "refs/heads/feature/x");
        String sig = computeSignature(nonDefaultPayload, SECRET);
        ResponseEntity<String> response = controller.receive(sig, "push", nonDefaultPayload);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verifyNoInteractions(orchestrationService);
    }

    @Test
    void unsupportedEventTypeIsIgnored() throws Exception {
        String sig = computeSignature(PAYLOAD, SECRET);
        ResponseEntity<String> response = controller.receive(sig, "create", PAYLOAD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verifyNoInteractions(orchestrationService);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static String computeSignature(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(
                mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}

