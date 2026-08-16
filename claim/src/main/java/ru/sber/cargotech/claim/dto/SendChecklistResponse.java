package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.DocumentValidationStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SendChecklistResponse(
    UUID claimId,
    boolean readyToSend,
    DocumentValidationStatus documentValidationStatus,
    boolean manualReviewRequired,
    Map<String, Boolean> checks,
    List<String> warnings
) {
}
