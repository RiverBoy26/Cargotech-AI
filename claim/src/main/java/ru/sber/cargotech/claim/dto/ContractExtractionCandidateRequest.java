package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import ru.sber.cargotech.claim.enums.ClauseType;
import ru.sber.cargotech.claim.enums.ContractExtractionField;

import java.math.BigDecimal;

public record ContractExtractionCandidateRequest(
    @NotNull ContractExtractionField field,
    String value,
    @NotBlank String source,
    @Positive Integer sourcePage,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
    @Size(max = 64) String clauseNumber,
    ClauseType clauseType
) {}
