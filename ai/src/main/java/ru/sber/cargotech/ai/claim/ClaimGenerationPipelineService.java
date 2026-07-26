package ru.sber.cargotech.ai.claim;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimPipelineRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimPipelineResponse;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailDecision;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.claim.guardrail.RuleBasedGuardrailService;
import ru.sber.cargotech.ai.claim.parser.ClaimResponseParser;
import ru.sber.cargotech.ai.claim.prompt.LoadingFailurePromptBuilder;
import ru.sber.cargotech.ai.claim.prompt.PaymentDelayPromptBuilder;
import ru.sber.cargotech.ai.gigachat.GigaChatClient;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.rag.RagSearchService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ClaimGenerationPipelineService {

    private final RagSearchService ragSearchService;
    private final PaymentDelayPromptBuilder paymentDelayPromptBuilder;
    private final LoadingFailurePromptBuilder loadingFailurePromptBuilder;
    private final GigaChatClient gigaChatClient;
    private final ClaimResponseParser claimResponseParser;
    private final RuleBasedGuardrailService guardrailService;

    public ClaimGenerationPipelineService(
            RagSearchService ragSearchService,
            PaymentDelayPromptBuilder paymentDelayPromptBuilder,
            LoadingFailurePromptBuilder loadingFailurePromptBuilder,
            GigaChatClient gigaChatClient,
            ClaimResponseParser claimResponseParser,
            RuleBasedGuardrailService guardrailService
    ) {
        this.ragSearchService = ragSearchService;
        this.paymentDelayPromptBuilder = paymentDelayPromptBuilder;
        this.loadingFailurePromptBuilder = loadingFailurePromptBuilder;
        this.gigaChatClient = gigaChatClient;
        this.claimResponseParser = claimResponseParser;
        this.guardrailService = guardrailService;
    }

    public GenerateClaimPipelineResponse generate(GenerateClaimPipelineRequest request) {
        validateRequest(request);

        List<String> ragWarnings = new ArrayList<>();
        boolean ragUsed = ragEnabled(request);

        GenerateClaimRequest enrichedRequest = buildEnrichedRequest(request, ragUsed, ragWarnings);
        List<GigaChatMessage> messages = buildPrompt(enrichedRequest);

        GigaChatChatResponse chatResponse = gigaChatClient.sendChat(
                messages,
                enrichedRequest.caseFacts().claimId(),
                operationName(enrichedRequest.caseFacts().claimType())
        );

        String rawModelResponse = chatResponse.firstContent();
        if (rawModelResponse == null || rawModelResponse.isBlank()) {
            throw new IllegalStateException("GigaChat returned empty claim generation response");
        }

        GenerateClaimResponse generatedClaim = claimResponseParser.parse(rawModelResponse);
        GuardrailResult guardrailResult = guardrailService.check(enrichedRequest, generatedClaim);

        return new GenerateClaimPipelineResponse(
                guardrailResult.decision() != GuardrailDecision.BLOCK,
                status(guardrailResult),
                enrichedRequest.caseFacts().claimType(),
                ragUsed,
                List.copyOf(ragWarnings),
                messages,
                rawModelResponse,
                generatedClaim,
                guardrailResult,
                Instant.now()
        );
    }

    private GenerateClaimRequest buildEnrichedRequest(
            GenerateClaimPipelineRequest request,
            boolean ragUsed,
            List<String> ragWarnings
    ) {
        GenerateClaimRequest base = request.toGenerateClaimRequest();

        if (!ragUsed) {
            return base;
        }

        GenerateClaimPipelineRequest.RagOptions ragOptions = request.ragOptions();

        if (ragOptions == null || isBlank(ragOptions.contractId())) {
            ragWarnings.add("rag_options.contract_id is empty; provided context fields are used");
            return base;
        }

        RagSearchService.ClaimRagContext ragContext = ragSearchService.retrieveClaimContext(
                request.caseFacts().claimType(),
                ragOptions.contractId(),
                ragOptions.clientId()
        );

        ragWarnings.addAll(ragContext.warnings());

        return new GenerateClaimRequest(
                request.caseFacts(),
                request.backendCalculation(),
                choose(ragContext.contractContext(), request.contractContext()),
                choose(ragContext.legalContext(), request.legalContext()),
                ragContext.templateContext() == null ? request.templateContext() : ragContext.templateContext(),
                choose(ragContext.similarExamples(), request.similarExamples())
        );
    }

    private List<GigaChatMessage> buildPrompt(GenerateClaimRequest request) {
        GenerateClaimRequest.ClaimType claimType = request.caseFacts().claimType();

        if (claimType == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            return paymentDelayPromptBuilder.build(request);
        }

        if (claimType == GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            return loadingFailurePromptBuilder.build(request);
        }

        throw new IllegalArgumentException("Unsupported claim_type for generation pipeline: " + claimType);
    }

    private boolean ragEnabled(GenerateClaimPipelineRequest request) {
        return request.ragOptions() != null && !Boolean.FALSE.equals(request.ragOptions().enabled());
    }

    private void validateRequest(GenerateClaimPipelineRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("GenerateClaimPipelineRequest is null");
        }

        if (request.caseFacts() == null) {
            throw new IllegalArgumentException("case_facts is required");
        }

        if (request.caseFacts().claimType() == null) {
            throw new IllegalArgumentException("case_facts.claim_type is required");
        }

        if (request.backendCalculation() == null) {
            throw new IllegalArgumentException("backend_calculation is required");
        }
    }

    private String operationName(GenerateClaimRequest.ClaimType claimType) {
        return "GENERATE_CLAIM_" + claimType.name();
    }

    private String status(GuardrailResult guardrailResult) {
        if (guardrailResult.decision() == GuardrailDecision.BLOCK) {
            return "BLOCKED";
        }

        if (guardrailResult.decision() == GuardrailDecision.REVIEW) {
            return "REVIEW_REQUIRED";
        }

        return "PASSED";
    }

    private <T> List<T> choose(List<T> preferred, List<T> fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }

        return fallback == null ? List.of() : fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
