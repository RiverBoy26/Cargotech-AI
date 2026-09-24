package ru.sber.cargotech.claim.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.ContractRequest;
import ru.sber.cargotech.claim.dto.ContractIntakeRequest;
import ru.sber.cargotech.claim.dto.ContractResponse;
import ru.sber.cargotech.claim.dto.ContractExtractionResponse;
import ru.sber.cargotech.claim.dto.SubmitContractExtractionRequest;
import ru.sber.cargotech.claim.dto.PageResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ContractService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
@Slf4j
public class ContractController {
    private final ContractService contractService;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('CONTRACT_READ')")
    public PageResponse<ContractResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        log.info("Вызов endpoint: list");
        return PageResponse.from(contractService.list(currentUserProvider.getRequiredUser(), pageable));
    }

    @GetMapping("/{contractId}")
    @PreAuthorize("hasAuthority('CONTRACT_READ')")
    public ContractResponse get(@PathVariable UUID contractId) {
        log.info("Вызов endpoint: get");
        return contractService.get(currentUserProvider.getRequiredUser(), contractId);
    }

    @GetMapping("/{contractId}/extraction")
    @PreAuthorize("hasAuthority('CONTRACT_READ')")
    public ContractExtractionResponse getExtraction(@PathVariable UUID contractId) {
        return contractService.getExtraction(currentUserProvider.getRequiredUser(), contractId);
    }

    @PostMapping("/{contractId}/extraction/results")
    @PreAuthorize("hasAuthority('CONTRACT_UPDATE')")
    public ContractExtractionResponse submitExtraction(
        @PathVariable UUID contractId,
        @Valid @RequestBody SubmitContractExtractionRequest request
    ) {
        return contractService.submitExtraction(currentUserProvider.getRequiredUser(), contractId, request);
    }

    @PostMapping("/{contractId}/extraction/confirm")
    @PreAuthorize("hasAuthority('CONTRACT_UPDATE')")
    public ContractExtractionResponse confirmExtraction(@PathVariable UUID contractId) {
        return contractService.confirmExtraction(currentUserProvider.getRequiredUser(), contractId);
    }

    @PostMapping("/{contractId}/rag/reindex")
    @PreAuthorize("hasAuthority('CONTRACT_UPDATE')")
    public ContractResponse reindexContractRag(@PathVariable UUID contractId) {
        return contractService.requestRagReindex(currentUserProvider.getRequiredUser(), contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONTRACT_CREATE')")
    public ContractResponse create(@Valid @RequestBody ContractIntakeRequest request) {
        log.info("Вызов endpoint: create");
        return contractService.create(currentUserProvider.getRequiredUser(), request);
    }

    @PatchMapping("/{contractId}")
    @PreAuthorize("hasAuthority('CONTRACT_UPDATE')")
    public ContractResponse update(
        @PathVariable UUID contractId,
        @Valid @RequestBody ContractRequest request
    ) {
        log.info("Вызов endpoint: update");
        return contractService.update(currentUserProvider.getRequiredUser(), contractId, request);
    }

    @DeleteMapping("/{contractId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CONTRACT_DELETE')")
    public void delete(@PathVariable UUID contractId) {
        log.info("Вызов endpoint: delete");
        contractService.delete(currentUserProvider.getRequiredUser(), contractId);
    }
}
