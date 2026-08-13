package ru.sber.cargotech.ai.claim.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.claim.ClaimGenerationPipelineService;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimPipelineRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimPipelineResponse;

@RestController
@RequestMapping("/api/ai/claims")
public class ClaimGenerationController {

    private final ClaimGenerationPipelineService claimGenerationPipelineService;

    public ClaimGenerationController(ClaimGenerationPipelineService claimGenerationPipelineService) {
        this.claimGenerationPipelineService = claimGenerationPipelineService;
    }

    @PostMapping("/generate")
    public GenerateClaimPipelineResponse generate(
            @RequestBody GenerateClaimPipelineRequest request,
            @RequestHeader(value = "X-CargoTech-Actor-User-Id", required = false) UUID actorUserId
    ) {
        return claimGenerationPipelineService.generate(request, actorUserId);
    }
}
