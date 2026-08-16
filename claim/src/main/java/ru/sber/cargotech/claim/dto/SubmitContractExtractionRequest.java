package ru.sber.cargotech.claim.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SubmitContractExtractionRequest(
    @NotNull List<@Valid ContractExtractionCandidateRequest> candidates
) {}
