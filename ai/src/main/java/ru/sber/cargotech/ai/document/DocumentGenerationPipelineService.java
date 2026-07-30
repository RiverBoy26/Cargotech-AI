package ru.sber.cargotech.ai.document;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimPipelineRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailDecision;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentPipelineResponse;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;
import ru.sber.cargotech.ai.document.guardrail.DocumentGuardrailService;
import ru.sber.cargotech.ai.document.parser.DocumentResponseParser;
import ru.sber.cargotech.ai.document.prompt.LoadingFailureActPromptBuilder;
import ru.sber.cargotech.ai.document.prompt.LoadingFailureNotificationPromptBuilder;
import ru.sber.cargotech.ai.gigachat.GigaChatClient;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.rag.RagSearchService;

import java.time.Instant;
import java.util.*;

@Service
public class DocumentGenerationPipelineService {

    private final RagSearchService ragSearchService;
    private final LoadingFailureNotificationPromptBuilder notificationPromptBuilder;
    private final LoadingFailureActPromptBuilder actPromptBuilder;
    private final GigaChatClient gigaChatClient;
    private final DocumentResponseParser parser;
    private final DocumentGuardrailService guardrailService;

    public DocumentGenerationPipelineService(
            RagSearchService ragSearchService,
            LoadingFailureNotificationPromptBuilder notificationPromptBuilder,
            LoadingFailureActPromptBuilder actPromptBuilder,
            GigaChatClient gigaChatClient,
            DocumentResponseParser parser,
            DocumentGuardrailService guardrailService
    ) {
        this.ragSearchService = ragSearchService;
        this.notificationPromptBuilder = notificationPromptBuilder;
        this.actPromptBuilder = actPromptBuilder;
        this.gigaChatClient = gigaChatClient;
        this.parser = parser;
        this.guardrailService = guardrailService;
    }

    public GenerateDocumentPipelineResponse generateNotification(GenerateClaimPipelineRequest request) {
        return generate(request, GenerateClaimResponse.DocumentType.NOTIFICATION);
    }

    public GenerateDocumentPipelineResponse generateAct(GenerateClaimPipelineRequest request) {
        return generate(request, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT);
    }

    private GenerateDocumentPipelineResponse generate(
            GenerateClaimPipelineRequest request,
            GenerateClaimResponse.DocumentType documentType
    ) {
        validateRequest(request);
        List<String> ragWarnings = new ArrayList<>();
        boolean ragUsed = ragEnabled(request);
        GenerateClaimRequest enrichedRequest = enrich(request, ragUsed, ragWarnings);

        List<GigaChatMessage> messages = documentType == GenerateClaimResponse.DocumentType.NOTIFICATION
                ? notificationPromptBuilder.build(enrichedRequest)
                : actPromptBuilder.build(enrichedRequest);

        GigaChatClient.ChatCallResult callResult = gigaChatClient.sendChatWithTrace(
                messages,
                enrichedRequest.caseFacts().claimId(),
                "GENERATE_DOCUMENT_" + documentType.name()
        );
        GigaChatChatResponse chatResponse = callResult.response();
        String raw = chatResponse.firstContent();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("GigaChat returned empty document generation response");
        }

        GenerateDocumentResponse generated = parser.parse(raw);
        GuardrailResult guardrail = guardrailService.check(enrichedRequest, generated, documentType);

        return new GenerateDocumentPipelineResponse(
                guardrail.decision() != GuardrailDecision.BLOCK,
                status(guardrail),
                documentType,
                ragUsed,
                List.copyOf(ragWarnings),
                callResult.requestId(),
                chatResponse.usage(),
                generated,
                guardrail,
                Instant.now()
        );
    }

    private GenerateClaimRequest enrich(
            GenerateClaimPipelineRequest request,
            boolean ragUsed,
            List<String> warnings
    ) {
        GenerateClaimRequest base = request.toGenerateClaimRequest();
        if (!ragUsed) return base;

        RagSearchService.ClaimRagContext context;
        try {
            context = ragSearchService.retrieveClaimContext(
                    request.caseFacts().claimType(),
                    request.ragOptions().contractId(),
                    request.ragOptions().clientId()
            );
        } catch (RuntimeException exception) {
            if (request.contractContext() != null && !request.contractContext().isEmpty()) {
                warnings.add("RAG retrieval failed; trusted provided context was used");
                return base;
            }
            throw new IllegalStateException("RAG retrieval failed and no provided contract_context is available", exception);
        }
        warnings.addAll(context.warnings());

        return new GenerateClaimRequest(
                request.caseFacts(),
                request.backendCalculation(),
                mergeContracts(request.contractContext(), context.contractContext()),
                mergeLegal(request.legalContext(), context.legalContext()),
                request.templateContext() == null ? context.templateContext() : request.templateContext(),
                mergeExamples(request.similarExamples(), context.similarExamples())
        );
    }

    private void validateRequest(GenerateClaimPipelineRequest request) {
        if (request == null || request.caseFacts() == null) {
            throw new IllegalArgumentException("case_facts is required");
        }
        if (request.caseFacts().claimType() != GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            throw new IllegalArgumentException("Only LOADING_FAILURE supports notification and act generation");
        }
        if (request.caseFacts().shipment() == null) {
            throw new IllegalArgumentException("case_facts.shipment is required");
        }
        if (!Boolean.TRUE.equals(request.caseFacts().shipment().failureConfirmedByDispatcher())) {
            throw new IllegalArgumentException("failure_confirmed_by_dispatcher must be true");
        }
        if (ragEnabled(request)) {
            if (request.ragOptions() == null || isBlank(request.ragOptions().contractId())) {
                throw new IllegalArgumentException("rag_options.contract_id is required when RAG is enabled");
            }
            if (isBlank(request.ragOptions().clientId())) {
                throw new IllegalArgumentException("rag_options.client_id is required when RAG is enabled");
            }
        }
    }

    private boolean ragEnabled(GenerateClaimPipelineRequest request) {
        return request.ragOptions() != null && !Boolean.FALSE.equals(request.ragOptions().enabled());
    }

    private List<GenerateClaimRequest.ContractContextChunk> mergeContracts(
            List<GenerateClaimRequest.ContractContextChunk> first,
            List<GenerateClaimRequest.ContractContextChunk> second
    ) {
        LinkedHashMap<String, GenerateClaimRequest.ContractContextChunk> map = new LinkedHashMap<>();
        for (GenerateClaimRequest.ContractContextChunk item : concat(first, second)) {
            if (item == null) continue;
            String key = !isBlank(item.chunkId()) ? item.chunkId() : item.clauseNumber() + "::" + item.text();
            map.putIfAbsent(key, item);
        }
        return List.copyOf(map.values());
    }

    private List<GenerateClaimRequest.LegalContextItem> mergeLegal(
            List<GenerateClaimRequest.LegalContextItem> first,
            List<GenerateClaimRequest.LegalContextItem> second
    ) {
        LinkedHashMap<String, GenerateClaimRequest.LegalContextItem> map = new LinkedHashMap<>();
        for (GenerateClaimRequest.LegalContextItem item : concat(first, second)) {
            if (item == null) continue;
            String key = !isBlank(item.chunkId()) ? item.chunkId() : item.lawCode() + "::" + item.article();
            map.putIfAbsent(key, item);
        }
        return List.copyOf(map.values());
    }

    private List<GenerateClaimRequest.SimilarExample> mergeExamples(
            List<GenerateClaimRequest.SimilarExample> first,
            List<GenerateClaimRequest.SimilarExample> second
    ) {
        LinkedHashMap<String, GenerateClaimRequest.SimilarExample> map = new LinkedHashMap<>();
        for (GenerateClaimRequest.SimilarExample item : concat(first, second)) {
            if (item == null) continue;
            String key = !isBlank(item.exampleId()) ? item.exampleId() : String.valueOf(item.structureSummary());
            map.putIfAbsent(key, item);
        }
        return List.copyOf(map.values());
    }

    private <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return result;
    }

    private String status(GuardrailResult result) {
        return result.decision() == GuardrailDecision.BLOCK
                ? "BLOCKED"
                : (result.decision() == GuardrailDecision.REVIEW ? "REVIEW_REQUIRED" : "PASSED");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
