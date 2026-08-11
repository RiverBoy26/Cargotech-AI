package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.PartyType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PartyResponse(
    UUID id,
    UUID organizationId,
    PartyType type,
    String name,
    String inn,
    String kpp,
    String ogrn,
    String legalAddress,
    String postalAddress,
    String email,
    String phone,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
