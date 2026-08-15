package ru.sber.cargotech.ai.claim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
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

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ClaimGenerationPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ClaimGenerationPipelineService.class);

    // Acceptance target is <= 60 seconds end-to-end. Keep an internal safety margin
    // for claim-service/gateway serialization and network overhead.
    static final long PIPELINE_BUDGET_MS = 55_000L;
    static final long INITIAL_LLM_TIMEOUT_MS = 50_000L;
    static final long REPAIR_LLM_MAX_TIMEOUT_MS = 27_000L;
    static final long REPAIR_MIN_TIMEOUT_MS = 20_000L;
    static final long FINALIZATION_RESERVE_MS = 3_000L;

    private static final Pattern PAYMENT_TERM_MEANING = Pattern.compile(
            "(?iu)(?:срок\\p{L}*\\s+оплат\\p{L}*|оплат\\p{L}*[^\\n]{0,100}(?:должн\\p{L}*|производ\\p{L}*|осуществл\\p{L}*|не\\s+позднее)|не\\s+позднее[^\\n]{0,80}оплат\\p{L}*)"
    );
    private static final Pattern CLAIM_RESPONSE_MEANING = Pattern.compile(
            "(?iu)(?:письменн\\p{L}*\\s+ответ\\p{L}*|ответ\\p{L}*[^\\n]{0,80}претензи\\p{L}*|претензи\\p{L}*[^\\n]{0,80}ответ\\p{L}*)"
    );
    private static final Pattern CONTRACT_PENALTY_MEANING = Pattern.compile(
            "(?iu)(?:неустойк\\p{L}*|пен(?:я|и|ей|ю))"
    );
    private static final String[] RU_MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

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
        return generate(request, null);
    }

    public GenerateClaimPipelineResponse generate(
            GenerateClaimPipelineRequest request,
            UUID actorUserId
    ) {
        long pipelineStartedNanos = System.nanoTime();
        validateRequest(request);

        List<String> ragWarnings = new ArrayList<>();
        boolean ragUsed = ragEnabled(request);

        EnrichmentResult enrichment = buildEnrichedRequest(request, ragUsed, ragWarnings);
        GenerateClaimRequest enrichedRequest = enrichment.request();
        List<GigaChatMessage> messages = buildPrompt(enrichedRequest);

        long initialTimeoutMs = initialTimeoutMillis(elapsedMillis(pipelineStartedNanos));
        if (initialTimeoutMs <= 0) {
            throw new IllegalStateException("Claim generation latency budget exhausted before initial LLM call");
        }
        GigaChatClient.ChatCallResult callResult = gigaChatClient.sendChatWithTrace(
                messages,
                enrichedRequest.caseFacts().claimId(),
                actorUserId,
                operationName(enrichedRequest.caseFacts().claimType()),
                initialTimeoutMs
        );
        GigaChatChatResponse chatResponse = callResult.response();

        String rawModelResponse = requireContent(
                chatResponse,
                "GigaChat returned empty claim generation response"
        );
        GenerateClaimResponse generatedClaim = normalizeCitationMetadata(
                enrichedRequest,
                claimResponseParser.parse(rawModelResponse)
        );
        GuardrailResult guardrailResult = guardrailService.check(enrichedRequest, generatedClaim);
        logGuardrailResult(
                "INITIAL",
                enrichedRequest.caseFacts().claimId(),
                callResult.requestId(),
                guardrailResult
        );
        GigaChatChatResponse.Usage totalUsage = chatResponse.usage();
        String requestId = callResult.requestId();

        if (guardrailResult.decision() == GuardrailDecision.BLOCK
                && enrichedRequest.caseFacts().claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            GenerateClaimResponse deterministicRepair = repairContractCitations(
                    enrichedRequest,
                    generatedClaim,
                    guardrailResult.errors()
            );
            if (deterministicRepair != generatedClaim) {
                generatedClaim = deterministicRepair;
                guardrailResult = guardrailService.check(enrichedRequest, generatedClaim);
                logGuardrailResult(
                        "DETERMINISTIC_REPAIR",
                        enrichedRequest.caseFacts().claimId(),
                        requestId,
                        guardrailResult
                );
            }
        }

        if (guardrailResult.decision() == GuardrailDecision.BLOCK) {
            long elapsedMs = elapsedMillis(pipelineStartedNanos);
            long repairTimeoutMs = repairTimeoutMillis(elapsedMs);

            if (repairTimeoutMs <= 0) {
                log.warn(
                        "Claim generation blocked; LLM repair skipped to preserve latency budget: caseId={}, requestId={}, elapsedMs={}, budgetMs={}, errorCount={}",
                        enrichedRequest.caseFacts().claimId(),
                        requestId,
                        elapsedMs,
                        PIPELINE_BUDGET_MS,
                        guardrailResult.errors() == null ? 0 : guardrailResult.errors().size()
                );
            } else {
                log.warn(
                        "Claim generation blocked; starting bounded repair: caseId={}, requestId={}, elapsedMs={}, repairTimeoutMs={}, errorCount={}",
                        enrichedRequest.caseFacts().claimId(),
                        requestId,
                        elapsedMs,
                        repairTimeoutMs,
                        guardrailResult.errors() == null ? 0 : guardrailResult.errors().size()
                );

                List<GigaChatMessage> repairMessages = buildRepairMessages(
                        enrichedRequest,
                        messages,
                        rawModelResponse,
                        generatedClaim,
                        guardrailResult.errors()
                );

                try {
                    GigaChatClient.ChatCallResult repairCall = gigaChatClient.sendChatWithTrace(
                            repairMessages,
                            enrichedRequest.caseFacts().claimId(),
                            actorUserId,
                            operationName(enrichedRequest.caseFacts().claimType()) + "_REPAIR",
                            repairTimeoutMs
                    );
                    GigaChatChatResponse repairResponse = repairCall.response();
                    String repairedRaw = requireContent(
                            repairResponse,
                            "GigaChat returned empty claim repair response"
                    );
                    generatedClaim = normalizeCitationMetadata(
                            enrichedRequest,
                            claimResponseParser.parse(repairedRaw)
                    );
                    guardrailResult = guardrailService.check(enrichedRequest, generatedClaim);
                    logGuardrailResult(
                            "REPAIR",
                            enrichedRequest.caseFacts().claimId(),
                            repairCall.requestId(),
                            guardrailResult
                    );
                    totalUsage = mergeUsage(totalUsage, repairResponse.usage());
                    requestId = repairCall.requestId();
                } catch (ResourceAccessException timeoutOrIo) {
                    if (!isTimeout(timeoutOrIo)) {
                        throw timeoutOrIo;
                    }
                    log.warn(
                            "Claim LLM repair timed out inside latency budget; returning BLOCKED draft: caseId={}, requestId={}, elapsedMs={}, repairTimeoutMs={}, exceptionType={}",
                            enrichedRequest.caseFacts().claimId(),
                            requestId,
                            elapsedMillis(pipelineStartedNanos),
                            repairTimeoutMs,
                            timeoutOrIo.getClass().getSimpleName()
                    );
                    guardrailResult = appendGuardrailWarning(
                            guardrailResult,
                            "LLM repair timed out within the claim generation latency budget"
                    );
                }
            }
        }

        long totalDurationMs = elapsedMillis(pipelineStartedNanos);
        log.info(
                "Claim generation final: caseId={}, requestId={}, success={}, status={}, decision={}, durationMs={}",
                enrichedRequest.caseFacts().claimId(),
                requestId,
                guardrailResult.decision() != GuardrailDecision.BLOCK,
                status(guardrailResult),
                guardrailResult.decision(),
                totalDurationMs
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
                enrichment.retrievedFragments(),
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
                    "Claim guardrail: stage={}, caseId={}, requestId={}, decision={}, errorCount={}, warningCount={}, errors={}",
                    stage,
                    caseId,
                    requestId,
                    result.decision(),
                    result.errors() == null ? 0 : result.errors().size(),
                    result.warnings() == null ? 0 : result.warnings().size(),
                    result.errors() == null ? List.of() : result.errors()
            );
        } else {
            log.info(
                    "Claim guardrail: stage={}, caseId={}, requestId={}, decision={}, errorCount={}, warningCount={}",
                    stage,
                    caseId,
                    requestId,
                    result.decision(),
                    result.errors() == null ? 0 : result.errors().size(),
                    result.warnings() == null ? 0 : result.warnings().size()
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
            GenerateClaimRequest request,
            List<GigaChatMessage> originalMessages,
            String blockedRawResponse,
            GenerateClaimResponse blockedResponse,
            List<String> errors
    ) {
        if (request.caseFacts().claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            return paymentDelayPromptBuilder.buildRepair(request, blockedResponse, errors);
        }

        List<GigaChatMessage> messages = new ArrayList<>(originalMessages);
        messages.add(new GigaChatMessage("assistant", blockedRawResponse));
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
                4. Для LOADING_FAILURE используй точную фразу «транспортное средство не было предоставлено к погрузке».
                5. Не используй термин «непредставление транспортного средства».
                6. Если во входе есть act_number и act_date, добавь LOADING_FAILURE_ACT с required=true и точными реквизитами.
                7. Не добавляй нормы, которых нет в legal_context, и не указывай в used_law_articles нормы, отсутствующие в claim_text.
                8. Верни только валидный JSON без markdown и текста вне JSON.
                """.formatted(String.join("\n- ", errors == null ? List.of() : errors))
        ));
        return messages;
    }

    long initialTimeoutMillis(long elapsedMs) {
        long remainingForInitial = PIPELINE_BUDGET_MS - elapsedMs - FINALIZATION_RESERVE_MS;
        return Math.max(0L, Math.min(INITIAL_LLM_TIMEOUT_MS, remainingForInitial));
    }

    long repairTimeoutMillis(long elapsedMs) {
        long remainingForRepair = PIPELINE_BUDGET_MS - elapsedMs - FINALIZATION_RESERVE_MS;
        long timeout = Math.min(REPAIR_LLM_MAX_TIMEOUT_MS, remainingForRepair);
        return timeout >= REPAIR_MIN_TIMEOUT_MS ? timeout : 0L;
    }

    private long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private GuardrailResult appendGuardrailWarning(GuardrailResult result, String warning) {
        List<String> warnings = new ArrayList<>(result.warnings() == null ? List.of() : result.warnings());
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
        return new GuardrailResult(
                result.decision(),
                result.errors() == null ? List.of() : result.errors(),
                List.copyOf(warnings)
        );
    }

    GenerateClaimResponse repairContractCitations(
            GenerateClaimRequest request,
            GenerateClaimResponse response,
            List<String> errors
    ) {
        if (!hasContractCitationRepairableError(errors)) {
            return response;
        }
        if (request == null
                || response == null
                || isBlank(response.claimText())
                || request.caseFacts() == null
                || request.caseFacts().claimType() != GenerateClaimRequest.ClaimType.PAYMENT_DELAY
                || request.caseFacts().contract() == null
                || isBlank(request.caseFacts().contract().contractNumber())
                || response.usedContractClauses() == null
                || response.usedContractClauses().isEmpty()) {
            return response;
        }

        Map<String, GenerateClaimRequest.ContractContextChunk> byChunkId = new java.util.HashMap<>();
        for (GenerateClaimRequest.ContractContextChunk chunk :
                request.contractContext() == null ? List.<GenerateClaimRequest.ContractContextChunk>of() : request.contractContext()) {
            if (chunk != null && !isBlank(chunk.chunkId())) {
                byChunkId.put(chunk.chunkId(), chunk);
            }
        }

        Map<String, List<String>> clausesByType = new LinkedHashMap<>();
        for (GenerateClaimResponse.UsedContractClause used : response.usedContractClauses()) {
            if (used == null || isBlank(used.chunkId()) || isBlank(used.clauseNumber())) {
                continue;
            }
            GenerateClaimRequest.ContractContextChunk source = byChunkId.get(used.chunkId());
            if (source == null
                    || isBlank(source.clauseType())
                    || !normalize(source.clauseNumber()).equals(normalize(used.clauseNumber()))) {
                continue;
            }
            if (!Set.of("PAYMENT_TERMS", "CLAIM_PROCEDURE", "PENALTY").contains(source.clauseType())) {
                continue;
            }
            clausesByType.computeIfAbsent(source.clauseType(), ignored -> new ArrayList<>());
            List<String> numbers = clausesByType.get(source.clauseType());
            if (!numbers.contains(source.clauseNumber())) {
                numbers.add(source.clauseNumber());
            }
        }

        clausesByType.values().forEach(numbers -> numbers.sort(String::compareTo));

        String repairedText = response.claimText();
        repairedText = appendCanonicalCitationToMeaningLine(
                repairedText,
                PAYMENT_TERM_MEANING,
                clausesByType.get("PAYMENT_TERMS"),
                request.caseFacts().contract()
        );
        repairedText = appendCanonicalCitationToMeaningLine(
                repairedText,
                CLAIM_RESPONSE_MEANING,
                clausesByType.get("CLAIM_PROCEDURE"),
                request.caseFacts().contract()
        );
        repairedText = appendCanonicalCitationToMeaningLine(
                repairedText,
                CONTRACT_PENALTY_MEANING,
                clausesByType.get("PENALTY"),
                request.caseFacts().contract()
        );

        if (repairedText.equals(response.claimText())) {
            return response;
        }

        return new GenerateClaimResponse(
                response.claimType(),
                repairedText,
                response.summaryForLawyer(),
                response.usedContractClauses(),
                response.usedLawArticles(),
                response.backendCalculationUsed(),
                response.attachments(),
                response.warnings(),
                response.manualReviewRequired()
        );
    }

    private boolean hasContractCitationRepairableError(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return false;
        }
        return errors.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(error -> error.startsWith("claim_text does not cite used contract clause:")
                        || error.startsWith("claim_text contract clause citation must include contract number")
                        || (error.startsWith("claim_text must cite a ")
                        && error.contains("contract clause in the same logical line")));
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String appendCanonicalCitationToMeaningLine(
            String text,
            Pattern meaningPattern,
            List<String> clauseNumbers,
            GenerateClaimRequest.ContractFacts contract
    ) {
        if (isBlank(text) || clauseNumbers == null || clauseNumbers.isEmpty() || contract == null
                || isBlank(contract.contractNumber())) {
            return text;
        }

        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!meaningPattern.matcher(line).find()) {
                continue;
            }
            if (line.contains(contract.contractNumber())
                    && clauseNumbers.stream().allMatch(number -> containsClauseReference(line, number))) {
                return text;
            }

            String citation = canonicalContractCitation(clauseNumbers, contract);
            lines[i] = appendBeforeTerminalPunctuation(line, " (" + citation + ")");
            return String.join("\n", lines);
        }
        return text;
    }

    private boolean containsClauseReference(String text, String clauseNumber) {
        if (isBlank(text) || isBlank(clauseNumber)) {
            return false;
        }
        String previousClauses = "(?:\\d+(?:\\.\\d+)+\\s*(?:,|;|и)\\s*)*";
        String marker = "(?:пункт(?:а|у|е|ом|ы|ов|ам|ами|ах)?|п\\.|пп\\.)\\s*"
                + previousClauses
                + Pattern.quote(clauseNumber);
        return Pattern.compile("(?iu)" + marker).matcher(text).find();
    }

    private String canonicalContractCitation(
            List<String> clauseNumbers,
            GenerateClaimRequest.ContractFacts contract
    ) {
        String clauses = clauseNumbers.size() == 1
                ? "п. " + clauseNumbers.get(0)
                : "пп. " + String.join(" и ", clauseNumbers);
        StringBuilder result = new StringBuilder(clauses)
                .append(" Договора № ")
                .append(contract.contractNumber());
        if (!isBlank(contract.contractDate())) {
            result.append(" от ").append(formatRussianDate(contract.contractDate()));
        }
        return result.toString();
    }

    private String appendBeforeTerminalPunctuation(String line, String addition) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        String trailingWhitespace = line.substring(end);
        if (end > 0 && ".!?;:".indexOf(line.charAt(end - 1)) >= 0) {
            return line.substring(0, end - 1) + addition + line.charAt(end - 1) + trailingWhitespace;
        }
        return line.substring(0, end) + addition + trailingWhitespace;
    }

    private String formatRussianDate(String rawDate) {
        if (isBlank(rawDate)) {
            return rawDate;
        }
        try {
            LocalDate date = LocalDate.parse(rawDate, DateTimeFormatter.ISO_LOCAL_DATE);
            return "%02d %s %d года".formatted(
                    date.getDayOfMonth(),
                    RU_MONTHS[date.getMonthValue() - 1],
                    date.getYear()
            );
        } catch (DateTimeParseException ignored) {
            return rawDate;
        }
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

    private EnrichmentResult buildEnrichedRequest(
            GenerateClaimPipelineRequest request,
            boolean ragUsed,
            List<String> ragWarnings
    ) {
        GenerateClaimRequest base = request.toGenerateClaimRequest();

        if (!ragUsed) {
            return new EnrichmentResult(base, List.of());
        }

        GenerateClaimPipelineRequest.RagOptions ragOptions = request.ragOptions();

        if (ragOptions == null || isBlank(ragOptions.contractId())) {
            ragWarnings.add("rag_options.contract_id is empty; provided context fields are used");
            return new EnrichmentResult(base, List.of());
        }

        RagSearchService.ClaimRagContext ragContext;
        try {
            ragContext = ragSearchService.retrieveClaimContext(
                    request.caseFacts().claimType(),
                    ragOptions.contractId(),
                    ragOptions.clientId(),
                    ragOptions.organizationId()
            );
        } catch (RuntimeException exception) {
            if (hasProvidedContractContext(request)) {
                ragWarnings.add("RAG retrieval failed; trusted provided context was used");
                return new EnrichmentResult(base, List.of());
            }
            throw new IllegalStateException("RAG retrieval failed and no provided contract_context is available", exception);
        }

        GenerateClaimRequest enriched = new GenerateClaimRequest(
                request.caseFacts(),
                request.backendCalculation(),
                mergeContractContext(request.contractContext(), ragContext.contractContext()),
                mergeLegalContext(request.legalContext(), ragContext.legalContext()),
                ragContext.templateContext() == null ? request.templateContext() : ragContext.templateContext(),
                mergeSimilarExamples(request.similarExamples(), ragContext.similarExamples())
        );
        addEffectiveRagWarnings(ragWarnings, ragContext.warnings(), enriched);
        return new EnrichmentResult(enriched, List.copyOf(ragContext.retrievedFragments()));
    }

    void addEffectiveRagWarnings(
            List<String> target,
            List<String> retrievalWarnings,
            GenerateClaimRequest finalRequest
    ) {
        if (retrievalWarnings == null || retrievalWarnings.isEmpty()) {
            return;
        }
        for (String warning : retrievalWarnings) {
            if (warning == null || warning.isBlank()) {
                continue;
            }
            if ("contract_context is empty".equals(warning)
                    && finalRequest.contractContext() != null
                    && !finalRequest.contractContext().isEmpty()) {
                continue;
            }
            if ("legal_context is empty".equals(warning)
                    && finalRequest.legalContext() != null
                    && !finalRequest.legalContext().isEmpty()) {
                continue;
            }
            if ("template_context is empty".equals(warning)
                    && finalRequest.templateContext() != null) {
                continue;
            }
            target.add(warning);
        }
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

    GenerateClaimResponse normalizeCitationMetadata(
            GenerateClaimRequest request,
            GenerateClaimResponse response
    ) {
        if (request == null || response == null) {
            return response;
        }

        boolean changed = false;
        List<GenerateClaimResponse.UsedContractClause> normalizedContract = new ArrayList<>();
        for (GenerateClaimResponse.UsedContractClause used :
                response.usedContractClauses() == null
                        ? List.<GenerateClaimResponse.UsedContractClause>of()
                        : response.usedContractClauses()) {
            if (used == null) {
                continue;
            }

            GenerateClaimRequest.ContractContextChunk exactByChunk = null;
            List<GenerateClaimRequest.ContractContextChunk> sameClause = new ArrayList<>();

            for (GenerateClaimRequest.ContractContextChunk allowed :
                    request.contractContext() == null
                            ? List.<GenerateClaimRequest.ContractContextChunk>of()
                            : request.contractContext()) {
                if (allowed == null) {
                    continue;
                }
                if (!isBlank(used.chunkId()) && used.chunkId().equals(allowed.chunkId())) {
                    exactByChunk = allowed;
                }
                if (!isBlank(used.clauseNumber())
                        && normalize(used.clauseNumber()).equals(normalize(allowed.clauseNumber()))) {
                    sameClause.add(allowed);
                }
            }

            GenerateClaimRequest.ContractContextChunk canonical = null;

            // If the model returned a valid chunk id, the backend source wins.
            if (exactByChunk != null
                    && (isBlank(used.clauseNumber())
                    || normalize(used.clauseNumber()).equals(normalize(exactByChunk.clauseNumber())))) {
                canonical = exactByChunk;
            } else if (sameClause.size() == 1) {
                // Most common LLM metadata error: the visible clause number is
                // correct, but it copied the chunk_id from a neighbouring chunk.
                // We can repair that deterministically without a second LLM call.
                canonical = sameClause.get(0);
            }

            if (canonical != null) {
                GenerateClaimResponse.UsedContractClause normalized =
                        new GenerateClaimResponse.UsedContractClause(
                                canonical.clauseNumber(),
                                canonical.chunkId(),
                                used.reason()
                        );
                normalizedContract.add(normalized);
                if (!normalized.equals(used)) {
                    changed = true;
                }
            } else {
                // Ambiguous/unknown citations remain untouched so the guardrail
                // can still block genuinely unsafe metadata.
                normalizedContract.add(used);
            }
        }

        List<GenerateClaimResponse.UsedLawArticle> normalizedLaw = new ArrayList<>();
        for (GenerateClaimResponse.UsedLawArticle used :
                response.usedLawArticles() == null
                        ? List.<GenerateClaimResponse.UsedLawArticle>of()
                        : response.usedLawArticles()) {
            if (used == null) {
                continue;
            }

            GenerateClaimRequest.LegalContextItem exactByChunk = null;
            List<GenerateClaimRequest.LegalContextItem> sameLaw = new ArrayList<>();

            for (GenerateClaimRequest.LegalContextItem allowed :
                    request.legalContext() == null
                            ? List.<GenerateClaimRequest.LegalContextItem>of()
                            : request.legalContext()) {
                if (allowed == null) {
                    continue;
                }
                if (!isBlank(used.chunkId()) && used.chunkId().equals(allowed.chunkId())) {
                    exactByChunk = allowed;
                }
                if (normalize(used.lawCode()).equals(normalize(allowed.lawCode()))
                        && normalize(used.article()).equals(normalize(allowed.article()))) {
                    sameLaw.add(allowed);
                }
            }

            GenerateClaimRequest.LegalContextItem canonical = null;
            if (exactByChunk != null
                    && normalize(used.lawCode()).equals(normalize(exactByChunk.lawCode()))
                    && normalize(used.article()).equals(normalize(exactByChunk.article()))) {
                canonical = exactByChunk;
            } else if (sameLaw.size() == 1) {
                canonical = sameLaw.get(0);
            }

            if (canonical != null) {
                GenerateClaimResponse.UsedLawArticle normalized =
                        new GenerateClaimResponse.UsedLawArticle(
                                canonical.chunkId(),
                                canonical.lawCode(),
                                canonical.article(),
                                used.reason()
                        );
                normalizedLaw.add(normalized);
                if (!normalized.equals(used)) {
                    changed = true;
                }
            } else {
                normalizedLaw.add(used);
            }
        }

        if (!changed) {
            return response;
        }

        return new GenerateClaimResponse(
                response.claimType(),
                response.claimText(),
                response.summaryForLawyer(),
                List.copyOf(normalizedContract),
                List.copyOf(normalizedLaw),
                response.backendCalculationUsed(),
                response.attachments(),
                response.warnings(),
                response.manualReviewRequired()
        );
    }

    List<GenerateClaimRequest.ContractContextChunk> mergeContractContext(
            List<GenerateClaimRequest.ContractContextChunk> primary,
            List<GenerateClaimRequest.ContractContextChunk> secondary
    ) {
        java.util.LinkedHashMap<String, GenerateClaimRequest.ContractContextChunk> merged = new java.util.LinkedHashMap<>();
        java.util.Set<String> verifiedClauses = new java.util.HashSet<>();
        for (GenerateClaimRequest.ContractContextChunk item : primary == null ? List.<GenerateClaimRequest.ContractContextChunk>of() : primary) {
            if (item == null) continue;
            if (!isBlank(item.clauseNumber())) verifiedClauses.add(normalize(item.clauseNumber()));
            String key = !isBlank(item.chunkId())
                    ? "id:" + item.chunkId()
                    : "clause:" + item.clauseNumber() + ":" + item.text();
            merged.putIfAbsent(key, item);
        }
        for (GenerateClaimRequest.ContractContextChunk item : secondary == null ? List.<GenerateClaimRequest.ContractContextChunk>of() : secondary) {
            if (item == null) continue;
            if (!isBlank(item.clauseNumber()) && verifiedClauses.contains(normalize(item.clauseNumber()))) {
                continue;
            }
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

    private record EnrichmentResult(
            GenerateClaimRequest request,
            List<RagSearchService.RetrievedFragment> retrievedFragments
    ) {
    }
}
