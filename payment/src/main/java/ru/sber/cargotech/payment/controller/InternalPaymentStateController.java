package ru.sber.cargotech.payment.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.payment.dto.PaymentAllocationResponse;
import ru.sber.cargotech.payment.dto.PaymentStateRequest;
import ru.sber.cargotech.payment.dto.PaymentStateResponse;
import ru.sber.cargotech.payment.enums.PaymentCheckStatus;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUserProvider;
import ru.sber.cargotech.payment.service.PaymentCascadeDeletionService;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class InternalPaymentStateController {

    private final PaymentMatchRepository matchRepository;
    private final PaymentCascadeDeletionService cascadeDeletionService;
    private final CurrentPaymentUserProvider currentUserProvider;

    @DeleteMapping("/by-claim/{claimId}/shipment/{shipmentId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLAIM_DELETE')")
    public void deleteForClaimAndShipment(
        @PathVariable UUID claimId,
        @PathVariable UUID shipmentId
    ) {
        log.info(
            "Каскадное удаление платежей: claimId={}, shipmentId={}",
            claimId,
            shipmentId
        );
        cascadeDeletionService.deleteForClaimAndShipment(
            currentUserProvider.getRequiredUser(),
            claimId,
            shipmentId
        );
    }

    @PostMapping("/payment-state")
    @PreAuthorize("hasAuthority('PAYMENT_READ')")
    public PaymentStateResponse getPaymentState(
            @RequestBody PaymentStateRequest request
    ) {
        log.info("Вызов endpoint: getPaymentState");
        BigDecimal claimPaid = safe(
                matchRepository.sumActiveByTarget(
                        PaymentTargetType.CLAIM,
                        request.claimId()
                )
        );

        BigDecimal shipmentPaid = safe(
                matchRepository.sumActiveByTarget(
                        PaymentTargetType.SHIPMENT,
                        request.shipmentId()
                )
        );

        BigDecimal paidAmount = claimPaid.add(shipmentPaid);

        BigDecimal remainingAmount = request.serviceAmount()
                .subtract(paidAmount)
                .max(BigDecimal.ZERO);

        var allocations = matchRepository.findActiveAllocationsByTargets(
                        PaymentTargetType.CLAIM,
                        request.claimId(),
                        PaymentTargetType.SHIPMENT,
                        request.shipmentId()
                ).stream()
                .map(allocation -> new PaymentAllocationResponse(
                        allocation.getPaymentDate(),
                        allocation.getMatchedAmount()
                ))
                .toList();

        return new PaymentStateResponse(
                request.claimId(),
                request.shipmentId(),
                request.serviceAmount(),
                paidAmount,
                remainingAmount,
                resolveStatus(
                        request.serviceAmount(),
                        paidAmount
                ),
                allocations
        );
    }

    private PaymentCheckStatus resolveStatus(
            BigDecimal expected,
            BigDecimal paid
    ) {
        log.info("Вызов endpoint: resolveStatus");
        if (paid.signum() == 0) {
            return PaymentCheckStatus.NOT_PAID;
        }

        int comparison = paid.compareTo(expected);

        if (comparison < 0) {
            return PaymentCheckStatus.PARTIALLY_PAID;
        }

        if (comparison == 0) {
            return PaymentCheckStatus.FULLY_PAID;
        }

        return PaymentCheckStatus.OVERPAID;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
