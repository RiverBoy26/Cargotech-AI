package ru.sber.cargotech.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentStateRequest(
        UUID claimId,
        UUID shipmentId,
        BigDecimal serviceAmount
) {
}
