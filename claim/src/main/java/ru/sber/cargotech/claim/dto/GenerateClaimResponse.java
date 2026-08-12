package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.DocumentValidationStatus;

import java.util.List;

public record GenerateClaimResponse(
        ClaimVersionResponse version,
        String summaryForLawyer,
        Boolean manualReviewRequired,
        List<String> warnings,
        DocumentValidationStatus validationStatus,
        String validationErrors,
        String usedSources
) {
    public GenerateClaimResponse(
        ClaimVersionResponse version,
        String summaryForLawyer,
        Boolean manualReviewRequired,
        List<String> warnings
    ) {
        this(version, summaryForLawyer, manualReviewRequired, warnings, null, null, null);
    }
}
