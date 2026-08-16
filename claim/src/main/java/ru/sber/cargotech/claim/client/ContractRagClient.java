package ru.sber.cargotech.claim.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ContractRagClient {

    private final RestClient restClient;

    public ContractRagClient(AiClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.ragReadTimeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader("X-Internal-Api-Key", properties.internalApiKey())
            .build();
    }

    public void replace(ReplaceContractChunksRequest request) {
        restClient.post()
            .uri("/internal/api/ai/rag/contracts/replace")
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                throw new IllegalStateException("AI RAG rejected contract replacement: HTTP " + httpResponse.getStatusCode().value());
            })
            .toBodilessEntity();
    }

    public void delete(DeleteContractChunksRequest request) {
        restClient.post()
            .uri("/internal/api/ai/rag/contracts/delete")
            .body(request)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                throw new IllegalStateException("AI RAG rejected contract deletion: HTTP " + httpResponse.getStatusCode().value());
            })
            .toBodilessEntity();
    }

    public record ReplaceContractChunksRequest(
        @JsonProperty("organization_id") UUID organizationId,
        @JsonProperty("client_id") UUID clientId,
        @JsonProperty("contract_id") UUID contractId,
        @JsonProperty("source_batch_id") String sourceBatchId,
        @JsonProperty("source_system") String sourceSystem,
        List<ContractChunk> chunks
    ) {}

    public record DeleteContractChunksRequest(
        @JsonProperty("organization_id") UUID organizationId,
        @JsonProperty("client_id") UUID clientId,
        @JsonProperty("contract_id") UUID contractId,
        @JsonProperty("source_id") UUID sourceId
    ) {}

    public record ContractChunk(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("rag_collection") String ragCollection,
        @JsonProperty("chunk_type") String chunkType,
        @JsonProperty("claim_type") String claimType,
        @JsonProperty("organization_id") String organizationId,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("contract_id") String contractId,
        @JsonProperty("contract_number") String contractNumber,
        @JsonProperty("contract_date") String contractDate,
        String contour,
        @JsonProperty("contract_type") String contractType,
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("source_title") String sourceTitle,
        @JsonProperty("section_title") String sectionTitle,
        @JsonProperty("section_path") String sectionPath,
        @JsonProperty("clause_number") String clauseNumber,
        @JsonProperty("clause_topic") String clauseTopic,
        String text,
        String citation,
        @JsonProperty("is_current") Boolean isCurrent,
        Map<String, Object> extra
    ) {}
}
