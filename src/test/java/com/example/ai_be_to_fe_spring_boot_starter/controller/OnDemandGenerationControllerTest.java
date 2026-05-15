package com.example.ai_be_to_fe_spring_boot_starter.controller;

import com.example.ai_be_to_fe_spring_boot_starter.model.GenerationStatus;
import com.example.ai_be_to_fe_spring_boot_starter.model.OnDemandGenerationRequest;
import com.example.ai_be_to_fe_spring_boot_starter.model.OnDemandGenerationResponse;
import com.example.ai_be_to_fe_spring_boot_starter.service.FePipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnDemandGenerationControllerTest {

    @Mock
    private FePipelineService fePipelineService;

    private OnDemandGenerationController controller;

    @BeforeEach
    void setUp() {
        controller = new OnDemandGenerationController(fePipelineService);
    }

    @Test
    void returnsErrorWhenBothFieldsAreBlank() {
        ResponseEntity<OnDemandGenerationResponse> response =
                controller.generate(new OnDemandGenerationRequest(null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(GenerationStatus.ERROR);
        verifyNoInteractions(fePipelineService);
    }

    @Test
    void returnsErrorWhenBothFieldsAreEmptyStrings() {
        ResponseEntity<OnDemandGenerationResponse> response =
                controller.generate(new OnDemandGenerationRequest("  ", "  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(GenerationStatus.ERROR);
        verifyNoInteractions(fePipelineService);
    }

    @Test
    void delegatesToPipelineWithCommitSha() throws Exception {
        OnDemandGenerationResponse pipelineResult = OnDemandGenerationResponse.success(
                "https://github.com/org/repo/pull/1", "ai/fe-sync-abc12345",
                new com.example.ai_be_to_fe_spring_boot_starter.model.AiFrontendResponse(
                        "Created user types", List.of()));

        when(fePipelineService.run("abc12345", null)).thenReturn(pipelineResult);

        ResponseEntity<OnDemandGenerationResponse> response =
                controller.generate(new OnDemandGenerationRequest("abc12345", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(GenerationStatus.SUCCESS);
        assertThat(response.getBody().pullRequestUrl()).isEqualTo("https://github.com/org/repo/pull/1");
        verify(fePipelineService).run("abc12345", null);
    }

    @Test
    void delegatesToPipelineWithPromptOnly() throws Exception {
        OnDemandGenerationResponse pipelineResult =
                OnDemandGenerationResponse.noChanges("No changes needed.");

        when(fePipelineService.run(null, "Add login form")).thenReturn(pipelineResult);

        ResponseEntity<OnDemandGenerationResponse> response =
                controller.generate(new OnDemandGenerationRequest(null, "Add login form"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(GenerationStatus.NO_CHANGES);
        verify(fePipelineService).run(null, "Add login form");
    }

    @Test
    void delegatesToPipelineWithBothFields() throws Exception {
        when(fePipelineService.run("abc12345", "Also add loading state"))
                .thenReturn(OnDemandGenerationResponse.noChanges("ok"));

        controller.generate(new OnDemandGenerationRequest("abc12345", "Also add loading state"));

        verify(fePipelineService).run("abc12345", "Also add loading state");
    }

    @Test
    void returns500WhenPipelineThrows() throws Exception {
        when(fePipelineService.run(any(), any())).thenThrow(new RuntimeException("GitHub down"));

        ResponseEntity<OnDemandGenerationResponse> response =
                controller.generate(new OnDemandGenerationRequest("abc12345", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo(GenerationStatus.ERROR);
        assertThat(response.getBody().summary()).contains("GitHub down");
    }
}

