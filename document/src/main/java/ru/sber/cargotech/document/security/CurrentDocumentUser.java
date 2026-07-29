package ru.sber.cargotech.document.security;

import java.util.List;
import java.util.UUID;

public record CurrentDocumentUser(
    UUID userId,
    UUID organizationId,
    String email,
    String fullName,
    List<String> roles,
    List<String> permissions
) {
}
