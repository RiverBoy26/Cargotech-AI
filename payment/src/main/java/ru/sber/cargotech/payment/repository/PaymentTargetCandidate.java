package ru.sber.cargotech.payment.repository;

import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentTargetCandidate(
    PaymentTargetType targetType,
    UUID targetId,
    String number,
    BigDecimal expectedAmount,
    BigDecimal remainingAmount
) {
}
