package ru.sber.cargotech.ai.rag;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.rag.dto.PaymentDelayRagContextRequest;
import ru.sber.cargotech.ai.rag.dto.PaymentDelayRagContextResponse;
import ru.sber.cargotech.ai.rag.dto.SearchRagChunksRequest;
import ru.sber.cargotech.ai.rag.dto.SearchRagChunksResponse;

@RestController
@RequestMapping("/api/ai/rag/search")
public class RagSearchController {

    private final RagSearchService ragSearchService;

    public RagSearchController(RagSearchService ragSearchService) {
        this.ragSearchService = ragSearchService;
    }

    @PostMapping("/chunks")
    public SearchRagChunksResponse searchChunks(@RequestBody SearchRagChunksRequest request) {
        return ragSearchService.searchRequest(request);
    }

    @PostMapping("/context/payment-delay")
    public PaymentDelayRagContextResponse paymentDelayContext(@RequestBody PaymentDelayRagContextRequest request) {
        return ragSearchService.retrievePaymentDelayContextRequest(request);
    }
}