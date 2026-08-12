package ru.sber.cargotech.ai.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.config.RagSearchProperties;
import ru.sber.cargotech.ai.gigachat.GigaChatEmbeddingClient;
import ru.sber.cargotech.ai.qdrant.QdrantRestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagSearchServiceTenantIsolationTest {

    @Test
    void appliesClientIdToEveryContractQuery() {
        GigaChatEmbeddingClient embeddingClient = mock(GigaChatEmbeddingClient.class);
        QdrantRestClient qdrantClient = mock(QdrantRestClient.class);
        RagSearchProperties properties = new RagSearchProperties();

        when(embeddingClient.embedOne(anyString())).thenReturn(List.of(0.1, 0.2));
        when(qdrantClient.queryPoints(anyList(), anyMap(), anyInt(), any())).thenReturn(Map.of("result", Map.of("points", List.of())));

        RagSearchService service = new RagSearchService(embeddingClient, qdrantClient, properties);
        service.retrieveClaimContext(GenerateClaimRequest.ClaimType.PAYMENT_DELAY, "contract-1", "client-A", "org-A");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> filters = ArgumentCaptor.forClass(Map.class);
        verify(qdrantClient, times(7)).queryPoints(anyList(), filters.capture(), anyInt(), any());

        List<Map<String, Object>> contractFilters = filters.getAllValues().stream()
                .filter(filter -> RagCollection.CONTRACT_CONTEXT.name().equals(filter.get("rag_collection")))
                .toList();

        assertThat(contractFilters).hasSize(4);
        assertThat(contractFilters).allSatisfy(filter -> {
            assertThat(filter).containsEntry("contract_id", "contract-1");
            assertThat(filter).containsEntry("client_id", "client-A");
            assertThat(filter).containsEntry("organization_id", "org-A");
            assertThat(filter.get("claim_type")).isEqualTo(List.of("PAYMENT_DELAY", "ALL"));
        });
    }

    @Test
    void rejectsContractRetrievalWithoutClientId() {
        RagSearchService service = new RagSearchService(
                mock(GigaChatEmbeddingClient.class),
                mock(QdrantRestClient.class),
                new RagSearchProperties()
        );

        assertThatThrownBy(() -> service.retrieveClaimContext(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "contract-1",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientId is required");
    }
}
