package ru.sber.cargotech.ai.rag;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksRequest;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksResponse;

@RestController
@RequestMapping("/api/ai/rag/index")
public class RagIndexController {

    private final RagIndexService ragIndexService;

    public RagIndexController(RagIndexService ragIndexService) {
        this.ragIndexService = ragIndexService;
    }

    @PostMapping("/chunks")
    public IndexRagChunksResponse indexChunks(@RequestBody IndexRagChunksRequest request) {
        return ragIndexService.indexRequest(request);
    }
}