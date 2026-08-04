package ru.sber.cargotech.document.dto;

import jakarta.validation.constraints.NotNull;
import ru.sber.cargotech.document.enums.DocumentEntityType;

import java.util.UUID;

public record DocumentLinkRequest(
    @NotNull DocumentEntityType entityType,
    @NotNull UUID entityId,
    String linkType
) {
}
