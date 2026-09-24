package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ClauseType;
import ru.sber.cargotech.claim.enums.ContractExtractionField;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ContractExtractionResponse(
    UUID contractId,
    UUID documentId,
    ContractExtractionStatus status,
    List<Candidate> candidates,
    OffsetDateTime confirmedAt,
    UUID confirmedBy
) {
    public record Candidate(
        ContractExtractionField field,
        String value,
        String source,
        Integer sourcePage,
        BigDecimal confidence,
        String clauseNumber,
        ClauseType clauseType,
        boolean manuallyEdited
    ) {}
}
