package ru.sber.cargotech.ai.document.api;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimPipelineRequest;
import ru.sber.cargotech.ai.document.DocumentGenerationPipelineService;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentPipelineResponse;

@RestController
@RequestMapping("/api/ai/documents/loading-failure")
public class LoadingFailureDocumentGenerationController {

    private final DocumentGenerationPipelineService pipelineService;

    public LoadingFailureDocumentGenerationController(DocumentGenerationPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/notification/generate")
    public GenerateDocumentPipelineResponse generateNotification(@RequestBody GenerateClaimPipelineRequest request) {
        return pipelineService.generateNotification(request);
    }

    @PostMapping("/act/generate")
    public GenerateDocumentPipelineResponse generateAct(@RequestBody GenerateClaimPipelineRequest request) {
        return pipelineService.generateAct(request);
    }
}
