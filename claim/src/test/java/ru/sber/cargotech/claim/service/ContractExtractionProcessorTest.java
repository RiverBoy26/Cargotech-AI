package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.client.DocumentTextClient;
import ru.sber.cargotech.claim.dto.SubmitContractExtractionRequest;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractExtractionProcessorTest {

    @Mock private DocumentTextClient documentTextClient;
    @Mock private ContractTextExtractionService extractionService;
    @Mock private ContractService contractService;

    @Test
    void readsContractTextOnlyInsideEventOrganizationScope() {
        UUID organizationId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID requestedBy = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        ContractExtractionRequestedEvent event = new ContractExtractionRequestedEvent(
            contractId, organizationId, requestedBy, documentId
        );
        DocumentTextClient.DocumentTextResponse document = new DocumentTextClient.DocumentTextResponse(
            documentId, "8.2. Оплата производится в течение 30 дней.", "APACHE_POI", 1
        );
        SubmitContractExtractionRequest result = new SubmitContractExtractionRequest(List.of());

        when(documentTextClient.getText(organizationId, documentId)).thenReturn(document);
        when(extractionService.extract(document.text(), document.extractionMethod())).thenReturn(result);

        new ContractExtractionProcessor(documentTextClient, extractionService, contractService).process(event);

        verify(documentTextClient).getText(organizationId, documentId);
        verify(contractService).completeAutomaticExtraction(
            organizationId, contractId, requestedBy, result
        );
    }
}
