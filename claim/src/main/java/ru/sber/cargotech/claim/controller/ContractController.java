package ru.sber.cargotech.claim.controller;

import jakarta.validation.Valid;
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
import ru.sber.cargotech.claim.dto.ContractResponse;
import ru.sber.cargotech.claim.dto.PageResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ContractService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {
    private final ContractService contractService;
    private final CurrentClaimUserProvider currentUserProvider;

    public ContractController(
        ContractService contractService,
        CurrentClaimUserProvider currentUserProvider
    ) {
        this.contractService = contractService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public PageResponse<ContractResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.from(contractService.list(currentUserProvider.getRequiredUser(), pageable));
    }

    @GetMapping("/{contractId}")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public ContractResponse get(@PathVariable UUID contractId) {
        return contractService.get(currentUserProvider.getRequiredUser(), contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLAIM_CREATE')")
    public ContractResponse create(@Valid @RequestBody ContractRequest request) {
        return contractService.create(currentUserProvider.getRequiredUser(), request);
    }

    @PatchMapping("/{contractId}")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ContractResponse update(
        @PathVariable UUID contractId,
        @Valid @RequestBody ContractRequest request
    ) {
        return contractService.update(currentUserProvider.getRequiredUser(), contractId, request);
    }

    @DeleteMapping("/{contractId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLAIM_DELETE')")
    public void delete(@PathVariable UUID contractId) {
        contractService.delete(currentUserProvider.getRequiredUser(), contractId);
    }
}
