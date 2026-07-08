package ru.sber.cargotech.payment.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimPaymentData(
    UUID id,
    UUID shipmentId,
    String claimNumber,
    String debtorInn,
    String shipmentOrderNumber,
    BigDecimal serviceAmount,
    String status
) {
}
