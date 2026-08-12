package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.enums.TermDayType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ContractResponse(
    UUID id,
    UUID organizationId,
    String number,
    UUID clientId,
    String clientName,
    UUID expeditorId,
    String expeditorName,
    LocalDate signedAt,
    LocalDate validFrom,
    LocalDate validTo,
    ContractStatus status,
    Integer paymentDays,
    TermDayType paymentDayType,
    PaymentStartEvent paymentStartEvent,
    PenaltyType penaltyType,
    BigDecimal penaltyRate,
    Integer claimResponseDays,
    TermDayType claimResponseDayType,
    String jurisdiction,
    UUID documentId,
    ContractExtractionStatus extractionStatus,
    OffsetDateTime extractionConfirmedAt,
    UUID extractionConfirmedBy,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
