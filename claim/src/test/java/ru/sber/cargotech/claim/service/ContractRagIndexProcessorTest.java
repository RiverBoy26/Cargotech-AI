package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.ContractRagClient;
import ru.sber.cargotech.claim.client.DocumentTextClient;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractRagIndexProcessorTest {

    @Mock private DocumentTextClient documentTextClient;
    @Mock private ContractRagClient ragClient;
    @Mock private ContractService contractService;

    @Test
    void successfulProcessingReplacesOriginalChunksAndMarksIndexed() {
        UUID organizationId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        ContractRagIndexRequestedEvent event = new ContractRagIndexRequestedEvent(
            contractId, organizationId, UUID.randomUUID(), documentId
        );
        when(contractService.getRagSnapshot(organizationId, contractId, documentId))
            .thenReturn(new ContractService.ContractRagSnapshot(
                contractId, organizationId, clientId, documentId, "Д-42", LocalDate.of(2026, 8, 13)
            ));
        when(documentTextClient.getText(organizationId, documentId)).thenReturn(new DocumentTextClient.DocumentTextResponse(
            documentId, "4.2. Оплата производится в течение 30 дней после подписания акта.", "PDFBOX", 1
        ));

        processor().index(event);

        ArgumentCaptor<ContractRagClient.ReplaceContractChunksRequest> request =
            ArgumentCaptor.forClass(ContractRagClient.ReplaceContractChunksRequest.class);
        verify(ragClient).replace(request.capture());
        assertThat(request.getValue().organizationId()).isEqualTo(organizationId);
        assertThat(request.getValue().chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.text()).startsWith("4.2. Оплата производится");
            assertThat(chunk.clauseNumber()).isEqualTo("4.2");
            assertThat(chunk.organizationId()).isEqualTo(organizationId.toString());
            assertThat(chunk.sourceId()).isEqualTo(documentId.toString());
        });
        verify(contractService).markRagIndexed(organizationId, contractId, documentId);
        verify(contractService, never()).markRagFailed(any(), any(), any(), any());
    }

    @Test
    void indexingFailureDoesNotChangeConfirmationAndMarksRagFailed() {
        UUID organizationId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        ContractRagIndexRequestedEvent event = new ContractRagIndexRequestedEvent(
            contractId, organizationId, UUID.randomUUID(), documentId
        );
        when(contractService.getRagSnapshot(organizationId, contractId, documentId))
            .thenReturn(new ContractService.ContractRagSnapshot(
                contractId, organizationId, UUID.randomUUID(), documentId, "Д-42", null
            ));
        when(documentTextClient.getText(organizationId, documentId)).thenReturn(new DocumentTextClient.DocumentTextResponse(
            documentId, "1.1. Стороны заключили настоящий договор.", "APACHE_POI", 1
        ));
        doThrow(new IllegalStateException("qdrant unavailable")).when(ragClient).replace(any());

        processor().index(event);

        verify(contractService).markRagFailed(
            organizationId, contractId, documentId, "CONTRACT_RAG_INDEXING_FAILED:IllegalStateException"
        );
        verify(contractService, never()).markRagIndexed(any(), any(), any());
    }

    private ContractRagIndexProcessor processor() {
        return new ContractRagIndexProcessor(
            documentTextClient, new ContractRagChunker(), ragClient, contractService
        );
    }
}
