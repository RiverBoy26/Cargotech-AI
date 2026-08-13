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
        String raw = requireContent(
                chatResponse,
                "GigaChat returned empty document generation response"
        );

        GenerateDocumentResponse generated = parser.parse(raw);
        GuardrailResult guardrail = guardrailService.check(enrichedRequest, generated, documentType);
        GigaChatChatResponse.Usage totalUsage = chatResponse.usage();
        String requestId = callResult.requestId();

        if (guardrail.decision() == GuardrailDecision.BLOCK) {
            List<GigaChatMessage> repairMessages = buildRepairMessages(
                    messages,
                    raw,
                    guardrail.errors(),
                    documentType
            );
            GigaChatClient.ChatCallResult repairCall = gigaChatClient.sendChatWithTrace(
                    repairMessages,
                    enrichedRequest.caseFacts().claimId(),
                    "GENERATE_DOCUMENT_" + documentType.name() + "_REPAIR"
            );
            GigaChatChatResponse repairResponse = repairCall.response();
            String repairedRaw = requireContent(
                    repairResponse,
                    "GigaChat returned empty document repair response"
            );
            generated = parser.parse(repairedRaw);
            guardrail = guardrailService.check(enrichedRequest, generated, documentType);
            totalUsage = mergeUsage(totalUsage, repairResponse.usage());
            requestId = repairCall.requestId();
        }

        return new GenerateDocumentPipelineResponse(
                guardrail.decision() != GuardrailDecision.BLOCK,
                status(guardrail),
                documentType,
                ragUsed,
                List.copyOf(ragWarnings),
                requestId,
                totalUsage,
                generated,
                guardrail,
                Instant.now()
        );
    }

    private String requireContent(GigaChatChatResponse response, String errorMessage) {
        String content = response == null ? null : response.firstContent();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException(errorMessage);
        }
        return content;
    }

    private List<GigaChatMessage> buildRepairMessages(
            List<GigaChatMessage> originalMessages,
            String blockedResponse,
            List<String> errors,
            GenerateClaimResponse.DocumentType documentType
    ) {
        List<GigaChatMessage> messages = new ArrayList<>(originalMessages);
        messages.add(new GigaChatMessage("assistant", blockedResponse));

        String typeRules = documentType == GenerateClaimResponse.DocumentType.NOTIFICATION
                ? """
                Для NOTIFICATION:
                - укажи дату уведомления строго из case_facts.claim_date;
                - обязательно укажи точный shipment.route;
                - напиши, что отправитель намерен составить акт в будущем;
                - не используй shipment.act_number и shipment.act_date.
                """
                : """
                Для LOADING_FAILURE_ACT:
                - document_title должен быть ровно «Акт о непредоставлении транспортного средства»;
                - обязательно укажи точный shipment.act_number и дату shipment.act_date;
                - прямо напиши, что акт в одностороннем порядке составлен case_facts.creditor.
                """;

        messages.add(new GigaChatMessage(
                "user",
                """
                Предыдущий JSON заблокирован детерминированными проверками.

                Исправь полный JSON-ответ, устранив каждую ошибку:
                - %s

                Общие правила ремонта:
                1. Верни полный объект GenerateDocumentResponse.
                2. Сохрани только факты из исходного входного JSON и RAG-контекста.
                3. Дословно перенеси обязательные номера, даты, маршрут, адрес и временное окно.
                4. Используй точную формулировку «транспортное средство не было предоставлено к погрузке».
                5. Не используй термин «непредставление транспортного средства».
                6. Не выдумывай представителей, водителя, марку, модель, госномер и причины нарушения.
                7. Верни только валидный JSON без markdown и текста вне JSON.

                %s
                """.formatted(
                        String.join("\n- ", errors == null ? List.of() : errors),
                        typeRules
                )
        ));
        return messages;
    }

    private GigaChatChatResponse.Usage mergeUsage(
            GigaChatChatResponse.Usage first,
            GigaChatChatResponse.Usage second
    ) {
        return new GigaChatChatResponse.Usage(
                sum(first == null ? null : first.promptTokens(), second == null ? null : second.promptTokens()),
                sum(first == null ? null : first.completionTokens(), second == null ? null : second.completionTokens()),
                sum(first == null ? null : first.totalTokens(), second == null ? null : second.totalTokens())
        );
    }

    private Integer sum(Integer first, Integer second) {
        if (first == null && second == null) return null;
        return (first == null ? 0 : first) + (second == null ? 0 : second);
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
                    request.ragOptions().clientId(),
                    request.ragOptions().organizationId()
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
