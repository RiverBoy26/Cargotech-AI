package ru.sber.cargotech.document.dto;

import java.util.Map;
import java.util.UUID;

public record TemplatePreviewRequest(
    UUID templateVersionId,
    Map<String, Object> data
) {
}
