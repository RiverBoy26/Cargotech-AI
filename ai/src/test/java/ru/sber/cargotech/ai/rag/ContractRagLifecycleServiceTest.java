package ru.sber.cargotech.ai.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import ru.sber.cargotech.ai.qdrant.QdrantRestClient;
import ru.sber.cargotech.ai.rag.dto.DeleteContractRagRequest;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksRequest;
import ru.sber.cargotech.ai.rag.dto.ReplaceContractRagRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractRagLifecycleServiceTest {

    @Test
    void replaceDeletesScopedOldPointsBeforeIndexingNewChunks() {
        RagIndexRequestMapper mapper = new RagIndexRequestMapper();
        RagIndexService indexService = mock(RagIndexService.class);
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        when(qdrant.deletePoints(anyMap())).thenReturn(Map.of("status", "ok"));
        when(indexService.indexChunks(anyList())).thenReturn(Map.of("indexed", 1));
        ContractRagLifecycleService service = new ContractRagLifecycleService(mapper, indexService, qdrant);

        var response = service.replace(request("org-A", "client-A", "contract-A"));

        InOrder order = inOrder(qdrant, indexService);
        order.verify(qdrant).deletePoints(anyMap());
        order.verify(indexService).indexChunks(anyList());
        assertThat(response.indexedChunks()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> filters = ArgumentCaptor.forClass(Map.class);
        verify(qdrant).deletePoints(filters.capture());
        assertThat(filters.getValue())
            .containsEntry("rag_collection", "CONTRACT_CONTEXT")
            .containsEntry("organization_id", "org-A")
            .containsEntry("client_id", "client-A")
            .containsEntry("contract_id", "contract-A");
    }

    @Test
    void rejectsCrossTenantChunkBeforeDeletingAnything() {
        RagIndexService indexService = mock(RagIndexService.class);
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        ContractRagLifecycleService service = new ContractRagLifecycleService(
            new RagIndexRequestMapper(), indexService, qdrant
        );

        assertThatThrownBy(() -> service.replace(request("org-B", "client-A", "contract-A")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("organization_id");
        verify(qdrant, never()).deletePoints(anyMap());
        verify(indexService, never()).indexChunks(anyList());
    }

    @Test
    void deleteUsesTenantContractAndOptionalSourceScope() {
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        when(qdrant.deletePoints(anyMap())).thenReturn(Map.of("status", "ok"));
        ContractRagLifecycleService service = new ContractRagLifecycleService(
            new RagIndexRequestMapper(), mock(RagIndexService.class), qdrant
        );

        service.delete(new DeleteContractRagRequest("org-A", "client-A", "contract-A", "document-A"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> filters = ArgumentCaptor.forClass(Map.class);
        verify(qdrant).deletePoints(filters.capture());
        assertThat(filters.getValue())
            .containsEntry("rag_collection", "CONTRACT_CONTEXT")
            .containsEntry("organization_id", "org-A")
            .containsEntry("client_id", "client-A")
            .containsEntry("contract_id", "contract-A")
            .containsEntry("source_id", "document-A");
    }

    private ReplaceContractRagRequest request(String scopeOrganization, String clientId, String contractId) {
        return new ReplaceContractRagRequest(
            scopeOrganization,
            clientId,
            contractId,
            "batch-1",
            "CLAIM_SERVICE_CONTRACT_RAG",
            List.of(new IndexRagChunksRequest.IndexRagChunk(
                "chunk-1",
                RagCollection.CONTRACT_CONTEXT,
                RagChunkType.PAYMENT_TERM,
                "PAYMENT_DELAY",
                "org-A",
                clientId,
                contractId,
                "Д-42",
                "2026-08-13",
                "EXPEDITOR_TO_CLIENT",
                "CLIENT_CONTRACT",
                "document-1",
                "Договор № Д-42",
                "Порядок расчётов",
                "Раздел 4",
                "4.2",
                "Условия оплаты",
                "Оплата производится в течение 30 дней.",
                "п. 4.2 Договора № Д-42",
                true,
                Map.of("source_kind", "original_contract")
            ))
        );
    }
}
