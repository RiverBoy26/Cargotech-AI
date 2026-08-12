package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.sber.cargotech.claim.client.DocumentTextClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContractExtractionProcessor {

    private final DocumentTextClient documentTextClient;
    private final ContractTextExtractionService extractionService;
    private final ContractService contractService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void process(ContractExtractionRequestedEvent event) {
        try {
            var document = documentTextClient.getText(event.documentId());
            var result = extractionService.extract(document.text(), document.extractionMethod());
            contractService.completeAutomaticExtraction(
                event.organizationId(), event.contractId(), event.requestedBy(), result
            );
        } catch (Exception exception) {
            log.warn(
                "Автоматический разбор договора завершился ошибкой: contractId={}, documentId={}, errorType={}",
                event.contractId(), event.documentId(), exception.getClass().getSimpleName()
            );
            contractService.markExtractionFailed(
                event.organizationId(), event.contractId(), event.requestedBy()
            );
        }
    }
}
