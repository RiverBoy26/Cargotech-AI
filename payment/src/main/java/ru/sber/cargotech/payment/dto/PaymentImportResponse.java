package ru.sber.cargotech.payment.dto;

import ru.sber.cargotech.payment.enums.PaymentImportStatus;
import ru.sber.cargotech.payment.enums.PaymentSourceSystem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PaymentImportResponse(
    UUID importId,
    PaymentSourceSystem sourceSystem,
    PaymentImportStatus status,
    int totalRows,
    int importedRows,
    int duplicateRows,
    int failedRows,
    List<String> errors,
    OffsetDateTime completedAt
) {
}
