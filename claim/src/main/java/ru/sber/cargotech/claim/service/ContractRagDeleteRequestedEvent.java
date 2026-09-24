package ru.sber.cargotech.claim.service;

import java.util.UUID;

public record ContractRagDeleteRequestedEvent(
    UUID contractId,
    UUID organizationId,
    UUID clientId,
    UUID sourceDocumentId
) {}
