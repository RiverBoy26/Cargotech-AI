package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ru.sber.cargotech.claim.enums.ShipmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ShipmentRequest(
    @NotBlank @Size(max = 128) String orderNumber,
    @NotNull UUID clientId,
    UUID expeditorId,
    @NotNull UUID contractId,
    @Size(max = 500) String routeFrom,
    @Size(max = 500) String routeTo,
    LocalDate loadingDate,
    LocalDate unloadingDate,
    LocalDate actSignedAt,
    LocalDate ttnSignedAt,
    LocalDate invoiceDate,
    LocalDate paymentStartEventDate,
    @NotNull @PositiveOrZero BigDecimal serviceAmount,
    @Size(max = 10) String currency,
    ShipmentStatus status,
    @Size(max = 255) String externalId
) {
}
