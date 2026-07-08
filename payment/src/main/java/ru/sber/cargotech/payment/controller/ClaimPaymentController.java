package ru.sber.cargotech.payment.controller;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.payment.dto.ClaimPaymentsResponse;
import ru.sber.cargotech.payment.dto.MarkPaidRequest;
import ru.sber.cargotech.payment.dto.MarkPaidResponse;
import ru.sber.cargotech.payment.dto.PreflightCheckResponse;
import ru.sber.cargotech.payment.security.CurrentPaymentUserProvider;
import ru.sber.cargotech.payment.service.PaymentCheckService;
import ru.sber.cargotech.payment.service.PaymentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/claims/{claimId}")
public class ClaimPaymentController {

    private final PaymentService paymentService;
    private final PaymentCheckService checkService;
    private final CurrentPaymentUserProvider userProvider;

    public ClaimPaymentController(
        PaymentService paymentService,
        PaymentCheckService checkService,
        CurrentPaymentUserProvider userProvider
    ) {
        this.paymentService = paymentService;
        this.checkService = checkService;
        this.userProvider = userProvider;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_READ')")
    public ClaimPaymentsResponse getClaimPayments(
        @PathVariable UUID claimId
    ) {
        return paymentService.findByClaim(
            claimId,
            userProvider.getRequiredUser()
        );
    }

    @PostMapping("/preflight-check")
    @PreAuthorize("hasAuthority('PAYMENT_READ')")
    public PreflightCheckResponse preflightCheck(
        @PathVariable UUID claimId,
        @RequestParam(required = false) String comment
    ) {
        return checkService.preflightCheck(
            claimId,
            comment,
            userProvider.getRequiredUser()
        );
    }

    @PostMapping("/mark-paid")
    @PreAuthorize("hasAuthority('PAYMENT_MARK_PAID')")
    public MarkPaidResponse markPaid(
        @PathVariable UUID claimId,
        @RequestBody @Valid MarkPaidRequest request
    ) {
        return checkService.markPaid(
            claimId,
            request,
            userProvider.getRequiredUser()
        );
    }
}
