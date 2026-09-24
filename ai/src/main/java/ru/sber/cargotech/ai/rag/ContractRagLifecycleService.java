package ru.sber.cargotech.ai.rag;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.qdrant.QdrantRestClient;
import ru.sber.cargotech.ai.rag.dto.DeleteContractRagRequest;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksRequest;
import ru.sber.cargotech.ai.rag.dto.ReplaceContractRagRequest;
import ru.sber.cargotech.ai.rag.dto.ReplaceContractRagResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContractRagLifecycleService {

    private final RagIndexRequestMapper requestMapper;
    private final RagIndexService indexService;
    private final QdrantRestClient qdrantRestClient;

    public ContractRagLifecycleService(
        RagIndexRequestMapper requestMapper,
        RagIndexService indexService,
        QdrantRestClient qdrantRestClient
    ) {
        this.requestMapper = requestMapper;
        this.indexService = indexService;
        this.qdrantRestClient = qdrantRestClient;
    }

    public ReplaceContractRagResponse replace(ReplaceContractRagRequest request) {
        validateTenant(request == null ? null : request.organizationId(), request == null ? null : request.clientId(), request == null ? null : request.contractId());
        IndexRagChunksRequest indexRequest = new IndexRagChunksRequest(
            request.sourceBatchId(), request.sourceSystem(), request.chunks()
        );
        List<RagChunk> chunks = requestMapper.toChunks(indexRequest);
        validateProductionChunks(request, chunks);

        Object deleteResponse = qdrantRestClient.deletePoints(contractFilters(
            request.organizationId(), request.clientId(), request.contractId(), null
        ));
        Object indexResponse = indexService.indexChunks(chunks);
        return new ReplaceContractRagResponse(
            true,
            request.organizationId(),
            request.clientId(),
            request.contractId(),
            chunks.size(),
            chunks.stream().map(RagChunk::chunkId).toList(),
            deleteResponse,
            indexResponse,
            Instant.now()
        );
    }

    public Object delete(DeleteContractRagRequest request) {
        validateTenant(request == null ? null : request.organizationId(), request == null ? null : request.clientId(), request == null ? null : request.contractId());
        return qdrantRestClient.deletePoints(contractFilters(
            request.organizationId(), request.clientId(), request.contractId(), request.sourceId()
        ));
    }

    private void validateProductionChunks(ReplaceContractRagRequest request, List<RagChunk> chunks) {
        for (RagChunk chunk : chunks) {
            if (chunk.ragCollection() != RagCollection.CONTRACT_CONTEXT) {
                throw new IllegalArgumentException("Only CONTRACT_CONTEXT chunks are accepted by contract replace");
            }
            requireEqual(request.organizationId(), chunk.organizationId(), "organization_id");
            requireEqual(request.clientId(), chunk.clientId(), "client_id");
            requireEqual(request.contractId(), chunk.contractId(), "contract_id");
            if (!Boolean.TRUE.equals(chunk.isCurrent())) {
                throw new IllegalArgumentException("Production contract chunks must have is_current=true");
            }
        }
    }

    private Map<String, Object> contractFilters(
        String organizationId,
        String clientId,
        String contractId,
        String sourceId
    ) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("rag_collection", RagCollection.CONTRACT_CONTEXT.name());
        filters.put("organization_id", organizationId);
        filters.put("client_id", clientId);
        filters.put("contract_id", contractId);
        if (sourceId != null && !sourceId.isBlank()) filters.put("source_id", sourceId);
        return filters;
    }

    private void validateTenant(String organizationId, String clientId, String contractId) {
        require(organizationId, "organization_id");
        require(clientId, "client_id");
        require(contractId, "contract_id");
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private void requireEqual(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Chunk " + field + " does not match replace scope");
        }
    }
}
