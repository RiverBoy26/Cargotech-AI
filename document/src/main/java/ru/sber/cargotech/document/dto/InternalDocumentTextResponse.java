package ru.sber.cargotech.document.dto;

import java.util.UUID;

public record InternalDocumentTextResponse(
    UUID documentId,
    String text,
    String extractionMethod,
    Integer pageCount
) {}
