package ru.sber.cargotech.claim.dto;

public record StatusChangeRequest(
    String reason,
    String cancellationReasonCode
) {
}
