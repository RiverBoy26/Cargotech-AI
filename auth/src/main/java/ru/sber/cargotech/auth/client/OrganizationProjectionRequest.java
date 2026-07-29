package ru.sber.cargotech.auth.client;

import java.util.UUID;

public record OrganizationProjectionRequest(
    UUID organizationId,
    String name,
    String inn,
    String kpp,
    String ogrn,
    String legalAddress,
    String postalAddress,
    String email,
    String phone,
    boolean active
) {
}
