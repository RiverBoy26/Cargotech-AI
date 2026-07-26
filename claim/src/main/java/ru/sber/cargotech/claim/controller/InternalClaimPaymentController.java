package ru.sber.cargotech.claim.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.claim.dto.ClaimPaymentContextResponse;
import ru.sber.cargotech.claim.dto.UpdateLastPaymentCheckRequest;
import ru.sber.cargotech.claim.security.CurrentClaimUser;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.InternalClaimPaymentService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/v1/claims")
@RequiredArgsConstructor
@Slf4j
public class InternalClaimPaymentController {

    private final InternalClaimPaymentService service;
    private final CurrentClaimUserProvider currentUserProvider;

    @GetMapping("/{claimId}/payment-context")
    @PreAuthorize(
            "hasAuthority('CLAIM_READ') or hasAuthority('PAYMENT_READ')"
    )
    public ClaimPaymentContextResponse getPaymentContext(
            @PathVariable UUID claimId
    ) {
        log.info("Вызов endpoint: getPaymentContext");
        CurrentClaimUser user = currentUserProvider.getRequiredUser();

        return service.getPaymentContext(
                user.organizationId(),
                claimId
        );
    }

    @GetMapping("/payment-contexts/by-payer-inn")
    @PreAuthorize(
            "hasAuthority('CLAIM_READ') or hasAuthority('PAYMENT_READ')"
    )
    public List<ClaimPaymentContextResponse> findByPayerInn(
            @RequestParam String payerInn
    ) {
        log.info("Вызов endpoint: findByPayerInn");
        CurrentClaimUser user = currentUserProvider.getRequiredUser();

        return service.findOpenByPayerInn(
                user.organizationId(),
                payerInn
        );
    }

    @GetMapping("/payment-contexts/mentioned")
    @PreAuthorize(
            "hasAuthority('CLAIM_READ') or hasAuthority('PAYMENT_READ')"
    )
    public List<ClaimPaymentContextResponse> findMentioned(
            @RequestParam String purpose
    ) {
        log.info("Вызов endpoint: findMentioned");
        CurrentClaimUser user = currentUserProvider.getRequiredUser();

        return service.findMentionedInPurpose(
                user.organizationId(),
                purpose
        );
    }

    @PatchMapping("/{claimId}/last-payment-check")
    @PreAuthorize(
            "hasAuthority('PAYMENT_READ') or hasAuthority('CLAIM_UPDATE')"
    )
    public ResponseEntity<Void> updateLastPaymentCheck(
            @PathVariable UUID claimId,
            @RequestBody UpdateLastPaymentCheckRequest request
    ) {
        log.info("Вызов endpoint: updateLastPaymentCheck");
        CurrentClaimUser user = currentUserProvider.getRequiredUser();

        service.updateLastPaymentCheck(
                user.organizationId(),
                claimId,
                request.checkId(),
                user.userId()
        );

        return ResponseEntity.noContent().build();
    }
}