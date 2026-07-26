package ru.sber.cargotech.payment.security;

import java.util.UUID;

public record CurrentPaymentUser(
    UUID userId,
    UUID organizationId
) {
}
