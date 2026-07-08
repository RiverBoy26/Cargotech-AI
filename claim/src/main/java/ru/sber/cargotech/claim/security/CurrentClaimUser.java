package ru.sber.cargotech.claim.security;

import java.util.UUID;

public record CurrentClaimUser(
    UUID userId,
    UUID organizationId
) {
}
