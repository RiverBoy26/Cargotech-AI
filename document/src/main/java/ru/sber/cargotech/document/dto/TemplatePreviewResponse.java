package ru.sber.cargotech.document.dto;

import java.util.List;
import java.util.UUID;

public record TemplatePreviewResponse(
    UUID templateId,
    UUID templateVersionId,
    String code,
    String name,
    String claimType,
    String content,
    List<String> variables,
    List<String> missingVariables
) {
}
