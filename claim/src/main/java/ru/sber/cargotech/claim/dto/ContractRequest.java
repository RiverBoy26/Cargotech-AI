package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ContractRequest(
    @NotBlank @Size(max = 128) String number,
    @NotNull UUID clientId,
    UUID expeditorId,
    LocalDate signedAt,
    LocalDate validFrom,
    LocalDate validTo,
    ContractStatus status,
    @Min(0) Integer paymentDays,
    PaymentStartEvent paymentStartEvent,
    PenaltyType penaltyType,
    @PositiveOrZero BigDecimal penaltyRate,
    @Min(0) Integer claimResponseDays,
    String jurisdiction,
    UUID documentId
) {
}
