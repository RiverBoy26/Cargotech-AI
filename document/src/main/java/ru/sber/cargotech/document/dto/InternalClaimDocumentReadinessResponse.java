package ru.sber.cargotech.document.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InternalClaimDocumentReadinessResponse(
    UUID claimId,
    boolean generatedClaimPresent,
    boolean calculationPdfPresent,
    boolean calculationXlsxPresent,
    int attachmentCount,
    List<DocumentReference> documents
) {
    public record DocumentReference(
        UUID documentId,
        String documentType,
        String linkType,
        String documentNumber,
        LocalDate documentDate
    ) {}
}
