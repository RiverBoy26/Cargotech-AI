package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ShipmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ShipmentResponse(
    UUID id,
    UUID organizationId,
    String orderNumber,
    UUID clientId,
    String clientName,
    UUID expeditorId,
    String expeditorName,
    UUID contractId,
    String contractNumber,
    String routeFrom,
    String routeTo,
    LocalDate loadingDate,
    LocalDate unloadingDate,
    LocalDate actSignedAt,
    LocalDate ttnSignedAt,
    LocalDate invoiceDate,
    LocalDate paymentStartEventDate,
    BigDecimal serviceAmount,
    String currency,
    ShipmentStatus status,
    String externalId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
