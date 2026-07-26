package ru.sber.cargotech.payment.dto;

import ru.sber.cargotech.payment.enums.PaymentMatchType;
import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentMatchResponse(
    UUID id,
    UUID paymentId,
    PaymentTargetType targetType,
    UUID targetId,
    BigDecimal matchedAmount,
    PaymentMatchType matchType,
    BigDecimal confidence,
    boolean active,
    UUID matchedBy,
    OffsetDateTime matchedAt,
    UUID unmatchedBy,
    OffsetDateTime unmatchedAt,
    String unmatchReason
) {
}
