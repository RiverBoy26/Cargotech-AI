package ru.sber.cargotech.auth.dto;

public record RequestMetadata(
    String ipAddress,
    String userAgent
) {
}
