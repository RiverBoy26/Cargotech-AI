package ru.sber.cargotech.payment.dto;

import java.util.UUID;

public record ReconciliationResponse(
    UUID runId,
    int paymentsChecked,
    int matchesCreated,
    int unmatchedCount
) {
}
