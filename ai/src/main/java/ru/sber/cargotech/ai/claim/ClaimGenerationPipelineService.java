package ru.sber.cargotech.ai.claim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ClaimGenerationPipelineService.class);

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

        GigaChatClient.ChatCallResult callResult = gigaChatClient.sendChatWithTrace(
                messages,
                enrichedRequest.caseFacts().claimId(),
                operationName(enrichedRequest.caseFacts().claimType())
        );
        GigaChatChatResponse chatResponse = callResult.response();

        String rawModelResponse = requireContent(
                chatResponse,
                "GigaChat returned empty claim generation response"
        );
        GenerateClaimResponse generatedClaim = claimResponseParser.parse(rawModelResponse);
        GuardrailResult guardrailResult = guardrailService.check(enrichedRequest, generatedClaim);
        logGuardrailResult(
                "INITIAL",
                enrichedRequest.caseFacts().claimId(),
                callResult.requestId(),
                guardrailResult
        );
        GigaChatChatResponse.Usage totalUsage = chatResponse.usage();
        String requestId = callResult.requestId();

        if (guardrailResult.decision() == GuardrailDecision.BLOCK) {
            log.warn(
                    "Claim generation blocked; starting repair: caseId={}, requestId={}, errors={}",
                    enrichedRequest.caseFacts().claimId(),
                    requestId,
                    guardrailResult.errors()
            );
            List<GigaChatMessage> repairMessages = buildRepairMessages(
                    messages,
                    rawModelResponse,
                    guardrailResult.errors()
            );
            GigaChatClient.ChatCallResult repairCall = gigaChatClient.sendChatWithTrace(
                    repairMessages,
                    enrichedRequest.caseFacts().claimId(),
                    operationName(enrichedRequest.caseFacts().claimType()) + "_REPAIR"
            );
            GigaChatChatResponse repairResponse = repairCall.response();
            String repairedRaw = requireContent(
                    repairResponse,
                    "GigaChat returned empty claim repair response"
            );
            generatedClaim = claimResponseParser.parse(repairedRaw);
            guardrailResult = guardrailService.check(enrichedRequest, generatedClaim);
            logGuardrailResult(
                    "REPAIR",
                    enrichedRequest.caseFacts().claimId(),
                    repairCall.requestId(),
                    guardrailResult
            );
            totalUsage = mergeUsage(totalUsage, repairResponse.usage());
            requestId = repairCall.requestId();
        }

        log.info(
                "Claim generation final: caseId={}, requestId={}, success={}, status={}, decision={}",
                enrichedRequest.caseFacts().claimId(),
                requestId,
                guardrailResult.decision() != GuardrailDecision.BLOCK,
                status(guardrailResult),
                guardrailResult.decision()
        );

        return new GenerateClaimPipelineResponse(
                guardrailResult.decision() != GuardrailDecision.BLOCK,
                status(guardrailResult),
                enrichedRequest.caseFacts().claimType(),
                ragUsed,
                List.copyOf(ragWarnings),
                requestId,
                totalUsage,
                generatedClaim,
                guardrailResult,
                Instant.now()
        );
    }


    private void logGuardrailResult(
            String stage,
            String caseId,
            String requestId,
            GuardrailResult result
    ) {
        if (result == null) {
            log.error(
                    "Claim guardrail result is null: stage={}, caseId={}, requestId={}",
                    stage,
                    caseId,
                    requestId
            );
            return;
        }

        if (result.decision() == GuardrailDecision.BLOCK) {
            log.warn(
                    "Claim guardrail: stage={}, caseId={}, requestId={}, decision={}, errors={}, warnings={}",
                    stage,
                    caseId,
                    requestId,
                    result.decision(),
                    result.errors(),
                    result.warnings()
            );
        } else {
            log.info(
                    "Claim guardrail: stage={}, caseId={}, requestId={}, decision={}, errors={}, warnings={}",
                    stage,
                    caseId,
                    requestId,
                    result.decision(),
                    result.errors(),
                    result.warnings()
            );
        }
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
            List<String> errors
    ) {
        List<GigaChatMessage> messages = new ArrayList<>(originalMessages);
        messages.add(new GigaChatMessage("assistant", blockedResponse));
        messages.add(new GigaChatMessage(
                "user",
                """
                Предыдущий JSON заблокирован детерминированными проверками.

                Исправь полный JSON-ответ, устранив каждую ошибку из списка:
                - %s

                Обязательные правила ремонта:
                1. Верни полный объект GenerateClaimResponse, а не фрагмент и не объяснение.
                2. Сохрани только факты из исходного входного JSON и RAG-контекста.
                3. Дословно перенеси все обязательные номера, даты, маршрут, адрес, временное окно и суммы.
                4. Для PAYMENT_DELAY обязательно укажи claim_number и claim_date, номер и дату договора, а также дату срока оплаты, если они есть во входе.
                5. Для PAYMENT_DELAY при claim_response_days > 0 укажи точный срок ответа в календарных днях с даты получения претензии.
                6. Для PAYMENT_DELAY при заполненном signatory заверши текст точными position и name; attachments верни пустым массивом.
                7. Если act_date есть, а act_number отсутствует, пиши «акт от <дата>» без символа № и пустого номера.
                8. Для LOADING_FAILURE используй точную фразу «транспортное средство не было предоставлено к погрузке».
                9. Не используй термин «непредставление транспортного средства».
                10. Если во входе есть act_number и act_date, добавь LOADING_FAILURE_ACT с required=true и точными реквизитами.
                11. Если legal_context не пуст, выбери минимум одну применимую норму, дословно вставь её citation в claim_text и добавь ту же норму в used_law_articles.
                12. Не добавляй нормы, которых нет в legal_context, и не указывай в used_law_articles нормы, отсутствующие в claim_text.
                13. Для PAYMENT_DELAY не добавляй банковские реквизиты и раздел «Приложения»; attachments верни пустым массивом.
                14. В claim_text не должно быть ISO-дат YYYY-MM-DD: преобразуй их в русскую письменную форму «07 августа 2026 года», не меняя саму календарную дату.
                15. В claim_text не должно быть технических enum/кодов UNPAID, PAID, PARTIALLY_PAID, UNKNOWN, RUB, CONTRACT_PENALTY, NONE. Вырази их смысл обычным русским языком.
                16. Денежные суммы в claim_text форматируй для документа: разделяй тысячи пробелами и не используй десятичную точку перед словом «рублей»; например «100 000 рублей 00 копеек». backend_calculation_used не изменяй.
                17. Правовую citation вставляй в естественную фразу «В соответствии со <citation> ...».
                18. Верни только валидный JSON без markdown и текста вне JSON.
                """.formatted(String.join("\n- ", errors == null ? List.of() : errors))
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

        RagSearchService.ClaimRagContext ragContext;
        try {
            ragContext = ragSearchService.retrieveClaimContext(
                    request.caseFacts().claimType(),
                    ragOptions.contractId(),
                    ragOptions.clientId()
            );
        } catch (RuntimeException exception) {
            if (hasProvidedContractContext(request)) {
                ragWarnings.add("RAG retrieval failed; trusted provided context was used");
                return base;
            }
            throw new IllegalStateException("RAG retrieval failed and no provided contract_context is available", exception);
        }

        ragWarnings.addAll(ragContext.warnings());

        return new GenerateClaimRequest(
                request.caseFacts(),
                request.backendCalculation(),
                mergeContractContext(request.contractContext(), ragContext.contractContext()),
                mergeLegalContext(request.legalContext(), ragContext.legalContext()),
                ragContext.templateContext() == null ? request.templateContext() : ragContext.templateContext(),
                mergeSimilarExamples(request.similarExamples(), ragContext.similarExamples())
        );
    }

    private boolean hasProvidedContractContext(GenerateClaimPipelineRequest request) {
        return request.contractContext() != null && !request.contractContext().isEmpty();
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

        if (ragEnabled(request)) {
            if (request.ragOptions() == null || isBlank(request.ragOptions().contractId())) {
                throw new IllegalArgumentException("rag_options.contract_id is required when RAG is enabled");
            }
            if (isBlank(request.ragOptions().clientId())) {
                throw new IllegalArgumentException("rag_options.client_id is required when RAG is enabled");
            }
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

    private List<GenerateClaimRequest.ContractContextChunk> mergeContractContext(
            List<GenerateClaimRequest.ContractContextChunk> primary,
            List<GenerateClaimRequest.ContractContextChunk> secondary
    ) {
        java.util.LinkedHashMap<String, GenerateClaimRequest.ContractContextChunk> merged = new java.util.LinkedHashMap<>();
        for (GenerateClaimRequest.ContractContextChunk item : concat(primary, secondary)) {
            if (item == null) continue;
            String key = !isBlank(item.chunkId())
                    ? "id:" + item.chunkId()
                    : "clause:" + item.clauseNumber() + ":" + item.text();
            merged.putIfAbsent(key, item);
        }
        return List.copyOf(merged.values());
    }

    private List<GenerateClaimRequest.LegalContextItem> mergeLegalContext(
            List<GenerateClaimRequest.LegalContextItem> primary,
            List<GenerateClaimRequest.LegalContextItem> secondary
    ) {
        java.util.LinkedHashMap<String, GenerateClaimRequest.LegalContextItem> merged = new java.util.LinkedHashMap<>();
        // RAG-элементы приоритетнее статического fallback: в них есть актуальные метаданные и источник.
        for (GenerateClaimRequest.LegalContextItem item : concat(secondary, primary)) {
            if (item == null) continue;
            String key = "law:" + normalize(item.lawCode()) + ":" + normalize(item.article());
            merged.putIfAbsent(key, item);
        }
        return List.copyOf(merged.values());
    }

    private List<GenerateClaimRequest.SimilarExample> mergeSimilarExamples(
            List<GenerateClaimRequest.SimilarExample> primary,
            List<GenerateClaimRequest.SimilarExample> secondary
    ) {
        java.util.LinkedHashMap<String, GenerateClaimRequest.SimilarExample> merged = new java.util.LinkedHashMap<>();
        for (GenerateClaimRequest.SimilarExample item : concat(primary, secondary)) {
            if (item == null) continue;
            String key = !isBlank(item.exampleId())
                    ? item.exampleId()
                    : String.valueOf(item.structureSummary());
            merged.putIfAbsent(key, item);
        }
        return List.copyOf(merged.values());
    }

    private <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
