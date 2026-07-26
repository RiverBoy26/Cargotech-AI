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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.*;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ClaimService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
@RequiredArgsConstructor
@Slf4j
public class ClaimController {
    private final ClaimService claimService;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public PageResponse<ClaimListItemResponse> list(
        @RequestParam(required = false) ClaimStatus status,
        @RequestParam(required = false) UUID creditorId,
        @RequestParam(required = false) UUID debtorId,
        @RequestParam(required = false) UUID assignedLawyerId,
        @RequestParam(required = false) String search,
        @PageableDefault(size = 50) Pageable pageable
    ) {
        log.info("Вызов endpoint: list");
        return PageResponse.from(claimService.list(
            currentUserProvider.getRequiredUser(),
            status,
            creditorId,
            debtorId,
            assignedLawyerId,
            search,
            pageable
        ));
    }

    @GetMapping("/{claimId}")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public ClaimDetailsResponse get(@PathVariable UUID claimId) {
        log.info("Вызов endpoint: get");
        return claimService.get(currentUserProvider.getRequiredUser(), claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLAIM_CREATE')")
    public ClaimDetailsResponse create(@Valid @RequestBody CreateClaimRequest request) {
        log.info("Вызов endpoint: create");
        return claimService.create(currentUserProvider.getRequiredUser(), request);
    }

    @PatchMapping("/{claimId}")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse update(
        @PathVariable UUID claimId,
        @Valid @RequestBody UpdateClaimRequest request
    ) {
        log.info("Вызов endpoint: update");
        return claimService.update(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @DeleteMapping("/{claimId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLAIM_DELETE')")
    public void delete(@PathVariable UUID claimId) {
        log.info("Вызов endpoint: delete");
        claimService.deleteDraft(currentUserProvider.getRequiredUser(), claimId);
    }

    @PostMapping("/{claimId}/submit-to-legal-review")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse submitToLegalReview(
        @PathVariable UUID claimId,
        @RequestBody(required = false) StatusChangeRequest request
    ) {
        log.info("Вызов endpoint: submitToLegalReview");
        return claimService.submitToLegalReview(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PostMapping("/{claimId}/approve")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse approve(
        @PathVariable UUID claimId,
        @RequestBody(required = false) StatusChangeRequest request
    ) {
        log.info("Вызов endpoint: approve");
        return claimService.approve(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PostMapping("/{claimId}/send")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse send(
        @PathVariable UUID claimId,
        @RequestBody(required = false) StatusChangeRequest request
    ) {
        log.info("Вызов endpoint: send");
        return claimService.send(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PostMapping("/{claimId}/cancel")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse cancel(
        @PathVariable UUID claimId,
        @RequestBody(required = false) StatusChangeRequest request
    ) {
        log.info("Вызов endpoint: cancel");
        return claimService.cancel(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PostMapping("/{claimId}/mark-paid")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse markPaid(
        @PathVariable UUID claimId,
        @RequestBody(required = false) StatusChangeRequest request
    ) {
        log.info("Вызов endpoint: markPaid");
        return claimService.markPaid(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PostMapping("/{claimId}/escalate-to-court")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse escalateToCourt(
        @PathVariable UUID claimId,
        @RequestBody(required = false) StatusChangeRequest request
    ) {
        log.info("Вызов endpoint: escalateToCourt");
        return claimService.escalateToCourt(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PostMapping("/{claimId}/close-in-court")
    @PreAuthorize("hasAuthority('CLAIM_UPDATE')")
    public ClaimDetailsResponse closeInCourt(
        @PathVariable UUID claimId,
        @RequestBody(required = false) StatusChangeRequest request
    ) {
        log.info("Вызов endpoint: closeInCourt");
        return claimService.closeInCourt(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @GetMapping("/{claimId}/status-history")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public List<StatusHistoryResponse> statusHistory(@PathVariable UUID claimId) {
        log.info("Вызов endpoint: statusHistory");
        return claimService.statusHistory(currentUserProvider.getRequiredUser(), claimId);
    }
}
