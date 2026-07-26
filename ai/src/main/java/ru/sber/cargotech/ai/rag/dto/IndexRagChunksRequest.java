package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.sber.cargotech.ai.rag.RagChunkType;
import ru.sber.cargotech.ai.rag.RagCollection;

import java.util.List;
import java.util.Map;

public record IndexRagChunksRequest(
        @JsonProperty("source_batch_id")
        String sourceBatchId,

        @JsonProperty("source_system")
        String sourceSystem,

        List<IndexRagChunk> chunks
) {
    public record IndexRagChunk(
            @JsonProperty("chunk_id")
            String chunkId,

            @JsonProperty("rag_collection")
            RagCollection ragCollection,

            @JsonProperty("chunk_type")
            RagChunkType chunkType,

            @JsonProperty("claim_type")
            String claimType,

            @JsonProperty("client_id")
            String clientId,

            @JsonProperty("contract_id")
            String contractId,

            @JsonProperty("contract_number")
            String contractNumber,

            @JsonProperty("contract_date")
            String contractDate,

            String contour,

            @JsonProperty("contract_type")
            String contractType,

            @JsonProperty("source_id")
            String sourceId,

            @JsonProperty("source_title")
            String sourceTitle,

            @JsonProperty("section_title")
            String sectionTitle,

            @JsonProperty("section_path")
            String sectionPath,

            @JsonProperty("clause_number")
            String clauseNumber,

            @JsonProperty("clause_topic")
            String clauseTopic,

            String text,
            String citation,

            @JsonProperty("is_current")
            Boolean isCurrent,

            Map<String, Object> extra
    ) {
    }
}