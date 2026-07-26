package ru.sber.cargotech.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record MarkPaidRequest(
    @NotNull UUID paymentId,
    @Size(max = 2000) String comment
) {
}
