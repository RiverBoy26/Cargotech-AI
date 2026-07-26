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
import ru.sber.cargotech.claim.dto.ContractResponse;
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
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public PageResponse<ContractResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        log.info("Вызов endpoint: list");
        return PageResponse.from(contractService.list(currentUserProvider.getRequiredUser(), pageable));
    }

    @GetMapping("/{contractId}")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public ContractResponse get(@PathVariable UUID contractId) {
        log.info("Вызов endpoint: get");
        return contractService.get(currentUserProvider.getRequiredUser(), contractId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLAIM_CREATE')")
    public ContractResponse create(@Valid @RequestBody ContractRequest request) {
        log.info("Вызов endpoint: create");
        return contractService.create(currentUserProvider.getRequiredUser(), request);
    }

    @PatchMapping("/{contractId}")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ContractResponse update(
        @PathVariable UUID contractId,
        @Valid @RequestBody ContractRequest request
    ) {
        log.info("Вызов endpoint: update");
        return contractService.update(currentUserProvider.getRequiredUser(), contractId, request);
    }

    @DeleteMapping("/{contractId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLAIM_DELETE')")
    public void delete(@PathVariable UUID contractId) {
        log.info("Вызов endpoint: delete");
        contractService.delete(currentUserProvider.getRequiredUser(), contractId);
    }
}
