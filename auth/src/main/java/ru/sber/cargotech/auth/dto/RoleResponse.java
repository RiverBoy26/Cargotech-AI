package ru.sber.cargotech.auth.dto;

import java.util.UUID;

public record RoleResponse(
    UUID id,
    String code,
    String name,
    String description
) {
}
