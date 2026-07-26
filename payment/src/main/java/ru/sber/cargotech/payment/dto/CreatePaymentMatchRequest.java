package ru.sber.cargotech.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentMatchRequest(
    @NotNull PaymentTargetType targetType,
    @NotNull UUID targetId,
    @NotNull @DecimalMin("0.01") BigDecimal matchedAmount,
    @Size(max = 2000) String comment
) {
}
