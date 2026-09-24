package ru.sber.cargotech.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentAllocationResponse(
        LocalDate paymentDate,
        BigDecimal amount
) {
}
