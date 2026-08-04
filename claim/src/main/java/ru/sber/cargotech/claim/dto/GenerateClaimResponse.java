package ru.sber.cargotech.claim.dto;

import java.util.List;

public record GenerateClaimResponse(
        ClaimVersionResponse version,
        String summaryForLawyer,
        Boolean manualReviewRequired,
        List<String> warnings
) {}
