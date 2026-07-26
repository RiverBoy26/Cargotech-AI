package ru.sber.cargotech.claim.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.ClaimVersionResponse;
import ru.sber.cargotech.claim.dto.CreateClaimVersionRequest;
import ru.sber.cargotech.claim.dto.VersionDiffResponse;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ClaimVersionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims/{claimId}/versions")
@RequiredArgsConstructor
@Slf4j
public class ClaimVersionController {
    private final ClaimVersionService versionService;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public List<ClaimVersionResponse> list(@PathVariable UUID claimId) {
        log.info("Вызов endpoint: list");
        return versionService.list(currentUserProvider.getRequiredUser(), claimId);
    }

    @GetMapping("/{versionId}")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public ClaimVersionResponse get(
        @PathVariable UUID claimId,
        @PathVariable UUID versionId
    ) {
        log.info("Вызов endpoint: get");
        return versionService.get(currentUserProvider.getRequiredUser(), claimId, versionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimVersionResponse create(
        @PathVariable UUID claimId,
        @Valid @RequestBody CreateClaimVersionRequest request
    ) {
        log.info("Вызов endpoint: create");
        return versionService.create(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PostMapping("/{versionId}/final")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimVersionResponse markFinal(
        @PathVariable UUID claimId,
        @PathVariable UUID versionId
    ) {
        log.info("Вызов endpoint: markFinal");
        return versionService.markFinal(currentUserProvider.getRequiredUser(), claimId, versionId);
    }

    @PostMapping("/{versionId}/restore")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimVersionResponse restore(
        @PathVariable UUID claimId,
        @PathVariable UUID versionId
    ) {
        log.info("Вызов endpoint: restore");
        return versionService.restore(currentUserProvider.getRequiredUser(), claimId, versionId);
    }

    @GetMapping("/{versionId}/diff")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public VersionDiffResponse diff(
        @PathVariable UUID claimId,
        @PathVariable UUID versionId
    ) {
        log.info("Вызов endpoint: diff");
        return versionService.diff(currentUserProvider.getRequiredUser(), claimId, versionId);
    }
}
