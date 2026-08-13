package ru.sber.cargotech.ai.rag;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.config.RagSearchProperties;
import ru.sber.cargotech.ai.gigachat.GigaChatEmbeddingClient;
import ru.sber.cargotech.ai.qdrant.QdrantRestClient;
import ru.sber.cargotech.ai.rag.dto.PaymentDelayRagContextRequest;
import ru.sber.cargotech.ai.rag.dto.PaymentDelayRagContextResponse;
import ru.sber.cargotech.ai.rag.dto.SearchRagChunksRequest;
import ru.sber.cargotech.ai.rag.dto.SearchRagChunksResponse;

import java.time.Instant;
import java.util.*;

@Service
public class RagSearchService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final GigaChatEmbeddingClient embeddingClient;
    private final QdrantRestClient qdrantRestClient;
    private final RagSearchProperties searchProperties;

    public RagSearchService(
            GigaChatEmbeddingClient embeddingClient,
            QdrantRestClient qdrantRestClient,
            RagSearchProperties searchProperties
    ) {
        this.embeddingClient = embeddingClient;
        this.qdrantRestClient = qdrantRestClient;
        this.searchProperties = searchProperties;
    }

    public SearchRagChunksResponse searchRequest(SearchRagChunksRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Search request is null");
        }

        int limit = normalizeLimit(request.limit());
        double minScore = request.minScore() == null ? 0.0 : request.minScore();

        List<RagSearchHit> filteredHits = search(
                request.query(),
                request.filters() == null ? Map.of() : request.filters(),
                limit,
                minScore
        );

        List<String> warnings = new ArrayList<>();

        if (filteredHits.isEmpty()) {
            warnings.add("RAG search returned no chunks");
        }

        return new SearchRagChunksResponse(
                true,
                request.query(),
                request.filters() == null ? Map.of() : request.filters(),
                limit,
                minScore,
                filteredHits.size(),
                filteredHits,
                warnings,
                Instant.now()
        );
    }

    public List<RagSearchHit> search(String query, Map<String, Object> filters, int limit) {
        return search(query, filters, limit, null);
    }

    public List<RagSearchHit> search(
            String query,
            Map<String, Object> filters,
            int limit,
            Double minScore
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("RAG query is blank");
        }

        if (minScore != null && (minScore < 0.0 || minScore > 1.0)) {
            throw new IllegalArgumentException("RAG minScore must be between 0 and 1");
        }

        int normalizedLimit = normalizeLimit(limit);

        List<Double> vector = embeddingClient.embedOne(query);
        Object raw = qdrantRestClient.queryPoints(vector, filters, normalizedLimit, minScore);

        return parseHits(raw);
    }

    public PaymentDelayRagContextResponse retrievePaymentDelayContextRequest(PaymentDelayRagContextRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PaymentDelayRagContextRequest is null");
        }

        if (request.contractId() == null || request.contractId().isBlank()) {
            throw new IllegalArgumentException("contract_id is required");
        }

        PaymentDelayRagContext context = retrievePaymentDelayContext(request.contractId(), request.clientId());

        return new PaymentDelayRagContextResponse(
                true,
                request.contractId(),
                request.clientId(),
                context,
                Instant.now()
        );
    }

    public PaymentDelayRagContext retrievePaymentDelayContext(String contractId, String clientId) {
        ClaimRagContext context = retrieveClaimContext(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                contractId,
                clientId
        );

        return new PaymentDelayRagContext(
                context.contractContext(),
                context.legalContext(),
                context.templateContext(),
                context.similarExamples(),
                context.retrievedFragments(),
                context.warnings()
        );
    }

    public ClaimRagContext retrieveClaimContext(
            GenerateClaimRequest.ClaimType claimType,
            String contractId,
            String clientId
    ) {
        return retrieveClaimContext(claimType, contractId, clientId, null);
    }

    public ClaimRagContext retrieveClaimContext(
            GenerateClaimRequest.ClaimType claimType,
            String contractId,
            String clientId,
            String organizationId
    ) {
        if (claimType == null) {
            throw new IllegalArgumentException("claimType is required");
        }

        if (contractId == null || contractId.isBlank()) {
            throw new IllegalArgumentException("contractId is required");
        }

        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required for tenant-isolated contract retrieval");
        }

        if (claimType == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            return retrievePaymentDelayClaimContext(contractId, clientId, organizationId);
        }

        if (claimType == GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            return retrieveLoadingFailureClaimContext(contractId, clientId, organizationId);
        }

        throw new IllegalArgumentException("Unsupported claimType for RAG context: " + claimType);
    }

    private ClaimRagContext retrievePaymentDelayClaimContext(String contractId, String clientId, String organizationId) {
        List<RagSearchHit> contractHits = new ArrayList<>();

        contractHits.addAll(search(
                "срок оплаты оказанных услуг дата подписания акта полный пакет документов",
                contractFilters("PAYMENT_DELAY", contractId, clientId, organizationId, RagChunkType.PAYMENT_TERM),
                2,
                searchProperties.getContractMinScore()
        ));

        contractHits.addAll(search(
                "неустойка пени штраф просрочка оплаты процент за каждый день",
                contractFilters("PAYMENT_DELAY", contractId, clientId, organizationId, RagChunkType.CONTRACT_PENALTY),
                2,
                searchProperties.getContractMinScore()
        ));

        contractHits.addAll(search(
                "претензионный порядок срок ответа на претензию мотивированный ответ",
                contractFilters("PAYMENT_DELAY", contractId, clientId, organizationId, RagChunkType.PRETRIAL_ORDER),
                2,
                searchProperties.getContractMinScore()
        ));

        if (contractHits.size() < 3) {
            contractHits.addAll(search(
                    "условия договора об оплате ответственности документах и претензионном порядке",
                    contractFilters("PAYMENT_DELAY", contractId, clientId, organizationId, null),
                    4,
                    searchProperties.getContractMinScore()
            ));
        }

        List<RagSearchHit> legalHits = search(
                "ГК РФ надлежащее исполнение обязательств срок оплаты проценты статья 395 договорная неустойка транспортная экспедиция статья 801",
                filters(
                        "rag_collection", RagCollection.LEGAL_CONTEXT.name(),
                        "claim_type", "PAYMENT_DELAY",
                        "is_current", true,
                        "auto_use", true
                ),
                8,
                searchProperties.getLegalMinScore()
        );

        // Backward compatibility for legal chunks indexed before auto_use metadata
        // became mandatory. Re-seeding will upgrade the payload, but generation
        // must not lose legal_context while old data is still present.
        if (legalHits.isEmpty()) {
            legalHits = search(
                    "ГК РФ надлежащее исполнение обязательств срок оплаты проценты статья 395 договорная неустойка транспортная экспедиция статья 801",
                    filters(
                            "rag_collection", RagCollection.LEGAL_CONTEXT.name(),
                            "claim_type", "PAYMENT_DELAY",
                            "is_current", true
                    ),
                    8,
                    searchProperties.getLegalMinScore()
            );
        }

        List<RagSearchHit> templateHits = search(
                "шаблон претензии о просрочке оплаты структура реквизиты договор расчет требование приложения",
                filters(
                        "rag_collection", RagCollection.TEMPLATE_CONTEXT.name(),
                        "claim_type", "PAYMENT_DELAY",
                        "is_current", true
                ),
                1,
                searchProperties.getTemplateMinScore()
        );

        List<RagSearchHit> exampleHits = search(
                "похожая претензия просрочка оплаты структура стиль сумма долга неустойка требование",
                filters(
                        "rag_collection", RagCollection.SIMILAR_EXAMPLE.name(),
                        "claim_type", "PAYMENT_DELAY",
                        "is_current", true
                ),
                1,
                searchProperties.getExampleMinScore()
        );

        return toClaimContext(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                contractHits,
                legalHits,
                templateHits,
                exampleHits
        );
    }

    private ClaimRagContext retrieveLoadingFailureClaimContext(String contractId, String clientId, String organizationId) {
        List<RagSearchHit> contractHits = new ArrayList<>();

        contractHits.addAll(search(
                "обязанность подать транспортное средство дата время место погрузки",
                contractFilters("LOADING_FAILURE", contractId, clientId, organizationId, RagChunkType.VEHICLE_SUPPLY_DUTY),
                2,
                searchProperties.getContractMinScore()
        ));

        contractHits.addAll(search(
                "штраф неустойка непредоставление транспортного средства срыв погрузки",
                contractFilters("LOADING_FAILURE", contractId, clientId, organizationId, RagChunkType.LOADING_FAILURE_PENALTY),
                2,
                searchProperties.getContractMinScore()
        ));

        contractHits.addAll(search(
                "претензионный порядок срок ответа на претензию мотивированный ответ",
                contractFilters("LOADING_FAILURE", contractId, clientId, organizationId, RagChunkType.PRETRIAL_ORDER),
                2,
                searchProperties.getContractMinScore()
        ));

        if (contractHits.size() < 3) {
            contractHits.addAll(search(
                    "условия договора о подаче транспорта погрузке ответственности и претензионном порядке",
                    contractFilters("LOADING_FAILURE", contractId, clientId, organizationId, null),
                    4,
                    searchProperties.getContractMinScore()
            ));
        }

        List<RagSearchHit> legalHits = search(
                "ГК РФ надлежащее исполнение обязательств договорная неустойка непредоставление транспортного средства",
                filters(
                        "rag_collection", RagCollection.LEGAL_CONTEXT.name(),
                        "claim_type", "LOADING_FAILURE",
                        "is_current", true,
                        "auto_use", true
                ),
                6,
                searchProperties.getLegalMinScore()
        );

        if (legalHits.isEmpty()) {
            legalHits = search(
                    "ГК РФ надлежащее исполнение обязательств договорная неустойка непредоставление транспортного средства",
                    filters(
                            "rag_collection", RagCollection.LEGAL_CONTEXT.name(),
                            "claim_type", "LOADING_FAILURE",
                            "is_current", true
                    ),
                    6,
                    searchProperties.getLegalMinScore()
            );
        }

        List<RagSearchHit> templateHits = search(
                "шаблон претензии о срыве погрузки непредоставлении транспортного средства структура",
                filters(
                        "rag_collection", RagCollection.TEMPLATE_CONTEXT.name(),
                        "claim_type", "LOADING_FAILURE",
                        "is_current", true
                ),
                1,
                searchProperties.getTemplateMinScore()
        );

        List<RagSearchHit> exampleHits = search(
                "похожая претензия срыв погрузки непредоставление транспортного средства стиль структура",
                filters(
                        "rag_collection", RagCollection.SIMILAR_EXAMPLE.name(),
                        "claim_type", "LOADING_FAILURE",
                        "is_current", true
                ),
                1,
                searchProperties.getExampleMinScore()
        );

        return toClaimContext(
                GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                contractHits,
                legalHits,
                templateHits,
                exampleHits
        );
    }

    private ClaimRagContext toClaimContext(
            GenerateClaimRequest.ClaimType claimType,
            List<RagSearchHit> contractHits,
            List<RagSearchHit> legalHits,
            List<RagSearchHit> templateHits,
            List<RagSearchHit> exampleHits
    ) {
        List<GenerateClaimRequest.ContractContextChunk> contractContext = contractHits.stream()
                .map(RagSearchHit::chunk)
                .filter(Objects::nonNull)
                .map(chunk -> new GenerateClaimRequest.ContractContextChunk(
                        chunk.chunkId(),
                        chunk.clauseNumber(),
                        chunk.sectionTitle(),
                        chunk.text()
                ))
                .distinct()
                .toList();

        List<GenerateClaimRequest.LegalContextItem> legalContext = legalHits.stream()
                .map(RagSearchHit::chunk)
                .filter(Objects::nonNull)
                .map(chunk -> new GenerateClaimRequest.LegalContextItem(
                        chunk.chunkId(),
                        str(chunk.extra().get("law_code")),
                        str(chunk.extra().get("article")),
                        str(chunk.extra().get("purpose")),
                        chunk.text(),
                        chunk.citation(),
                        str(chunk.extra().get("verified_at")),
                        str(chunk.extra().get("applicability"))
                ))
                .distinct()
                .toList();

        GenerateClaimRequest.TemplateContext templateContext = templateHits.stream()
                .findFirst()
                .map(RagSearchHit::chunk)
                .filter(Objects::nonNull)
                .map(chunk -> new GenerateClaimRequest.TemplateContext(
                        str(chunk.extra().get("template_id")),
                        str(chunk.extra().get("template_name")),
                        claimType,
                        toStringList(chunk.extra().get("template_structure"))
                ))
                .orElse(null);

        List<GenerateClaimRequest.SimilarExample> similarExamples = exampleHits.stream()
                .map(RagSearchHit::chunk)
                .filter(Objects::nonNull)
                .map(chunk -> new GenerateClaimRequest.SimilarExample(
                        str(chunk.extra().get("example_id")),
                        claimType,
                        str(chunk.extra().get("usage_rule")),
                        str(chunk.extra().get("structure_summary"))
                ))
                .distinct()
                .toList();

        List<String> warnings = new ArrayList<>();

        List<RetrievedFragment> retrievedFragments = concatHits(
                contractHits,
                legalHits,
                templateHits,
                exampleHits
        ).stream()
                .filter(Objects::nonNull)
                .filter(hit -> hit.chunk() != null)
                .map(hit -> new RetrievedFragment(
                        firstNotBlank(hit.chunk().sourceId(), hit.chunk().contractId()),
                        firstNotBlank(hit.chunk().chunkId(), hit.pointId()),
                        hit.score(),
                        hit.chunk().text()
                ))
                .filter(fragment -> fragment.chunkId() != null)
                .distinct()
                .toList();

        if (contractContext.isEmpty()) {
            warnings.add("contract_context is empty");
        }

        if (legalContext.isEmpty()) {
            warnings.add("legal_context is empty");
        }

        if (templateContext == null) {
            warnings.add("template_context is empty");
        }

        return new ClaimRagContext(
                contractContext,
                legalContext,
                templateContext,
                similarExamples,
                retrievedFragments,
                warnings
        );
    }

    @SafeVarargs
    private static List<RagSearchHit> concatHits(List<RagSearchHit>... groups) {
        List<RagSearchHit> result = new ArrayList<>();
        for (List<RagSearchHit> group : groups) {
            if (group != null) {
                result.addAll(group);
            }
        }
        return result;
    }

    private static String firstNotBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    @SuppressWarnings("unchecked")
    private List<RagSearchHit> parseHits(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return List.of();
        }

        Object result = rawMap.get("result");

        List<?> points;

        if (result instanceof Map<?, ?> resultMap && resultMap.get("points") instanceof List<?> list) {
            points = list;
        } else if (result instanceof List<?> list) {
            points = list;
        } else {
            return List.of();
        }

        List<RagSearchHit> hits = new ArrayList<>();

        for (Object item : points) {
            if (!(item instanceof Map<?, ?> point)) {
                continue;
            }

            Object payloadObject = point.get("payload");

            if (!(payloadObject instanceof Map<?, ?> payloadRaw)) {
                continue;
            }

            Map<String, Object> payload = new LinkedHashMap<>();

            for (Map.Entry<?, ?> entry : payloadRaw.entrySet()) {
                payload.put(String.valueOf(entry.getKey()), entry.getValue());
            }

            RagChunk chunk = RagChunk.fromPayload(payload);

            hits.add(new RagSearchHit(
                    str(point.get("id")),
                    doubleValue(point.get("score")),
                    chunk
            ));
        }

        return hits;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit <= 0) {
            throw new IllegalArgumentException("RAG limit must be positive");
        }

        return Math.min(limit, MAX_LIMIT);
    }

    private Map<String, Object> filters(Object... args) {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("Filters args must be key-value pairs");
        }

        Map<String, Object> filters = new LinkedHashMap<>();

        for (int i = 0; i < args.length; i += 2) {
            filters.put(String.valueOf(args[i]), args[i + 1]);
        }

        return filters;
    }

    private Map<String, Object> contractFilters(
            String claimType,
            String contractId,
            String clientId,
            String organizationId,
            RagChunkType chunkType
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rag_collection", RagCollection.CONTRACT_CONTEXT.name());
        result.put("claim_type", List.of(claimType, "ALL"));
        result.put("contract_id", contractId);
        result.put("client_id", clientId);
        if (organizationId != null && !organizationId.isBlank()) {
            result.put("organization_id", organizationId);
        }
        if (chunkType != null) result.put("chunk_type", chunkType.name());
        result.put("is_current", true);
        return result;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return Double.parseDouble(String.valueOf(value));
    }

    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }

        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .toList();
        }

        return List.of(String.valueOf(value));
    }

    public record ClaimRagContext(
            List<GenerateClaimRequest.ContractContextChunk> contractContext,
            List<GenerateClaimRequest.LegalContextItem> legalContext,
            GenerateClaimRequest.TemplateContext templateContext,
            List<GenerateClaimRequest.SimilarExample> similarExamples,
            List<RetrievedFragment> retrievedFragments,
            List<String> warnings
    ) {
    }

    public record RetrievedFragment(
            String documentId,
            String chunkId,
            Double score,
            String text
    ) {
    }

    public record PaymentDelayRagContext(
            List<GenerateClaimRequest.ContractContextChunk> contractContext,
            List<GenerateClaimRequest.LegalContextItem> legalContext,
            GenerateClaimRequest.TemplateContext templateContext,
            List<GenerateClaimRequest.SimilarExample> similarExamples,
            List<RetrievedFragment> retrievedFragments,
            List<String> warnings
    ) {
    }
}
