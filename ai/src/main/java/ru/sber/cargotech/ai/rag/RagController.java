package ru.sber.cargotech.ai.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConditionalOnProperty(prefix = "ai.diagnostics", name = "endpoints-enabled", havingValue = "true")
@RestController
@RequestMapping("/api/ai/rag")
public class RagController {

    private final RagIndexService ragIndexService;
    private final RagSearchService ragSearchService;
    private final SampleRagChunksFactory sampleRagChunksFactory;

    public RagController(
            RagIndexService ragIndexService,
            RagSearchService ragSearchService,
            SampleRagChunksFactory sampleRagChunksFactory
    ) {
        this.ragIndexService = ragIndexService;
        this.ragSearchService = ragSearchService;
        this.sampleRagChunksFactory = sampleRagChunksFactory;
    }

    @PostMapping("/demo/seed-payment-delay")
    public Map<String, Object> seedPaymentDelayChunks() {
        List<RagChunk> chunks = sampleRagChunksFactory.paymentDelayChunks();
        Object qdrantResponse = ragIndexService.indexChunks(chunks);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("indexed_chunks", chunks.size());
        result.put("chunk_ids", chunks.stream().map(RagChunk::chunkId).toList());
        result.put("qdrant_response", qdrantResponse);
        result.put("checkedAt", Instant.now().toString());

        return result;
    }

    @GetMapping("/demo/payment-delay-context")
    public Map<String, Object> paymentDelayContext(
            @RequestParam(defaultValue = "contract_45_2026") String contractId,
            @RequestParam(defaultValue = "demo_client") String clientId
    ) {
        RagSearchService.PaymentDelayRagContext context = ragSearchService.retrievePaymentDelayContext(contractId, clientId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("contract_id", contractId);
        result.put("client_id", clientId);
        result.put("rag_context", context);
        result.put("checkedAt", Instant.now().toString());

        return result;
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody RagSearchRequest request) {
        List<RagSearchHit> hits = ragSearchService.search(
                request.query(),
                request.filters() == null ? Map.of() : request.filters(),
                request.limit() == null ? 5 : request.limit()
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("hits", hits);
        result.put("checkedAt", Instant.now().toString());

        return result;
    }

    public record RagSearchRequest(
            String query,
            Map<String, Object> filters,
            Integer limit
    ) {
    }
}