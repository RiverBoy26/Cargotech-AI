package ru.sber.cargotech.ai.rag;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.config.QdrantProperties;
import ru.sber.cargotech.ai.gigachat.GigaChatEmbeddingClient;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatEmbeddingsResponse;
import ru.sber.cargotech.ai.qdrant.QdrantRestClient;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksRequest;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class RagIndexService {

    private static final int EMBEDDING_BATCH_SIZE = 16;

    private final GigaChatEmbeddingClient embeddingClient;
    private final QdrantRestClient qdrantRestClient;
    private final QdrantProperties qdrantProperties;
    private final RagIndexRequestMapper requestMapper;

    public RagIndexService(
            GigaChatEmbeddingClient embeddingClient,
            QdrantRestClient qdrantRestClient,
            QdrantProperties qdrantProperties,
            RagIndexRequestMapper requestMapper
    ) {
        this.embeddingClient = embeddingClient;
        this.qdrantRestClient = qdrantRestClient;
        this.qdrantProperties = qdrantProperties;
        this.requestMapper = requestMapper;
    }

    public IndexRagChunksResponse indexRequest(IndexRagChunksRequest request) {
        List<RagChunk> chunks = requestMapper.toChunks(request);
        Object qdrantResponse = indexChunks(chunks);

        return new IndexRagChunksResponse(
                true,
                request.sourceBatchId(),
                chunks.size(),
                chunks.stream().map(RagChunk::chunkId).toList(),
                qdrantResponse,
                Instant.now()
        );
    }

    public Object indexChunks(List<RagChunk> chunks) {
        validateChunks(chunks);

        List<Map<String, Object>> allPoints = new ArrayList<>();

        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            List<RagChunk> batch = chunks.subList(start, end);

            List<String> textsForEmbedding = batch.stream()
                    .map(RagChunk::embeddingText)
                    .toList();

            GigaChatEmbeddingsResponse embeddingsResponse = embeddingClient.embed(textsForEmbedding);

            if (embeddingsResponse.data() == null || embeddingsResponse.data().size() != batch.size()) {
                throw new IllegalStateException("Embeddings count does not match chunks count");
            }

            for (int i = 0; i < batch.size(); i++) {
                RagChunk chunk = batch.get(i);
                GigaChatEmbeddingsResponse.EmbeddingData embeddingData = embeddingsResponse.data().get(i);

                if (embeddingData.embedding() == null || embeddingData.embedding().isEmpty()) {
                    throw new IllegalStateException("Empty embedding for chunk_id=" + chunk.chunkId());
                }

                validateEmbeddingDimension(chunk.chunkId(), embeddingData.embedding());

                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", deterministicPointId(chunk.chunkId()));
                point.put("vector", embeddingData.embedding());
                point.put("payload", chunk.toPayload());

                allPoints.add(point);
            }
        }

        List<Object> qdrantResponses = new ArrayList<>();

        for (int start = 0; start < allPoints.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, allPoints.size());
            qdrantResponses.add(qdrantRestClient.upsertPoints(allPoints.subList(start, end)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexed_points", allPoints.size());
        result.put("batch_size", EMBEDDING_BATCH_SIZE);
        result.put("batch_count", qdrantResponses.size());
        result.put("vector_size", qdrantProperties.getVectorSize());
        result.put("responses", qdrantResponses);

        return result;
    }

    private void validateChunks(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("RAG chunks are empty");
        }

        Set<String> chunkIds = new HashSet<>();

        for (RagChunk chunk : chunks) {
            if (chunk == null) {
                throw new IllegalArgumentException("RAG chunk is null");
            }

            if (chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                throw new IllegalArgumentException("chunk_id is required");
            }

            if (!chunkIds.add(chunk.chunkId())) {
                throw new IllegalArgumentException("Duplicate chunk_id: " + chunk.chunkId());
            }

            if (chunk.ragCollection() == null) {
                throw new IllegalArgumentException("rag_collection is required for chunk_id=" + chunk.chunkId());
            }

            if (chunk.chunkType() == null) {
                throw new IllegalArgumentException("chunk_type is required for chunk_id=" + chunk.chunkId());
            }

            if (chunk.text() == null || chunk.text().isBlank()) {
                throw new IllegalArgumentException("text is required for chunk_id=" + chunk.chunkId());
            }
        }
    }

    private void validateEmbeddingDimension(String chunkId, List<Double> embedding) {
        Integer expectedVectorSize = qdrantProperties.getVectorSize();

        if (expectedVectorSize == null) {
            throw new IllegalStateException("qdrant.vector-size is not configured");
        }

        if (embedding.size() != expectedVectorSize) {
            throw new IllegalStateException("Embedding dimension mismatch for chunk_id=" + chunkId
                    + ". Expected " + expectedVectorSize + ", actual " + embedding.size()
                    + ". Check GIGACHAT_EMBEDDINGS_MODEL and QDRANT_VECTOR_SIZE.");
        }
    }

    private String deterministicPointId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunk_id is required");
        }

        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
