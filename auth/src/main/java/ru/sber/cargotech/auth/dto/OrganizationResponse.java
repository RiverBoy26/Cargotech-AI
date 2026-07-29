package ru.sber.cargotech.auth.dto;

import ru.sber.cargotech.auth.enums.OrganizationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrganizationResponse(
    UUID id,
    String name,
    String inn,
    String kpp,
    String ogrn,
    String legalAddress,
    String postalAddress,
    String email,
    String phone,
    OrganizationStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
