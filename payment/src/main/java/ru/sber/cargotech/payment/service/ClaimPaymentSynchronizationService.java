package ru.sber.cargotech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.sber.cargotech.payment.client.ClaimClient;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaimPaymentSynchronizationService {

    private final ClaimClient claimClient;

    public void synchronizeAfterCommit(UUID claimId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            synchronize(claimId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronize(claimId);
                }
            }
        );
    }

    private void synchronize(UUID claimId) {
        try {
            claimClient.syncPaymentState(claimId);
        } catch (RuntimeException exception) {
            // The payment is already committed. Keep the operation successful and
            // leave an actionable error for the next reconciliation/retry.
            log.error(
                "Не удалось синхронизировать claim после фиксации платежа: claimId={}",
                claimId,
                exception
            );
        }
    }
}
