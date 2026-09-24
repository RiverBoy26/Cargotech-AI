package ru.sber.cargotech.claim.service;

import java.util.UUID;

public record ContractExtractionRequestedEvent(
    UUID contractId,
    UUID organizationId,
    UUID requestedBy,
    UUID documentId
) {}
