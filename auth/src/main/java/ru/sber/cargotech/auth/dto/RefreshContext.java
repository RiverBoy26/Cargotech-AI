package ru.sber.cargotech.auth.dto;

import java.util.UUID;

public record RefreshContext(
        UUID userId,
        UUID organizationId
) {
}
