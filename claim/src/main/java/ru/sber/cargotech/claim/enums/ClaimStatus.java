package ru.sber.cargotech.claim.enums;

public enum ClaimStatus {
    DRAFT,
    PENDING_LEGAL_REVIEW,
    LEGAL_APPROVED,
    SENT,
    AWAITING_RESPONSE,
    PAID,
    ESCALATED_TO_COURT,
    CANCELLED,
    CANCELLED_PAID,
    CLOSED_IN_COURT
}
