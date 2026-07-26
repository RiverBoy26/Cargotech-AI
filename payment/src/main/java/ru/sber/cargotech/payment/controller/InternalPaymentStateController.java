package ru.sber.cargotech.payment.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.payment.dto.PaymentStateRequest;
import ru.sber.cargotech.payment.dto.PaymentStateResponse;
import ru.sber.cargotech.payment.enums.PaymentCheckStatus;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class InternalPaymentStateController {

    private final PaymentMatchRepository matchRepository;

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

        return new PaymentStateResponse(
                request.claimId(),
                request.shipmentId(),
                request.serviceAmount(),
                paidAmount,
                remainingAmount,
                resolveStatus(
                        request.serviceAmount(),
                        paidAmount
                )
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