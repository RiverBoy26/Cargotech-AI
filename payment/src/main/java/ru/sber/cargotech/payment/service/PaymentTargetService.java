package ru.sber.cargotech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.sber.cargotech.payment.client.ClaimClient;
import ru.sber.cargotech.payment.dto.ClaimPaymentContextResponse;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.exception.PaymentException;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentTargetService {

    private final ClaimClient claimClient;
    private final PaymentMatchRepository matchRepository;

    public boolean exists(
            PaymentTargetType targetType,
            UUID targetId
    ) {
        log.debug("Проверка цели сопоставления: targetType={}, targetId={}", targetType, targetId);

        if (targetType != PaymentTargetType.CLAIM) {
            throw PaymentException.unprocessable(
                    "Ручное сопоставление поддерживает только CLAIM"
            );
        }

        claimClient.getPaymentContext(targetId);
        return true;
    }

    public Optional<BigDecimal> remainingAmount(
            PaymentTargetType targetType,
            UUID targetId
    ) {
        log.debug("Расчёт остатка по цели: targetType={}, targetId={}", targetType, targetId);

        if (targetType != PaymentTargetType.CLAIM) {
            throw PaymentException.unprocessable(
                    "В MVP ручное сопоставление поддерживает только CLAIM"
            );
        }

        ClaimPaymentContextResponse claim =
                claimClient.getPaymentContext(targetId);

        BigDecimal paidAmount =
                safe(matchRepository.sumActiveByTarget(
                        PaymentTargetType.CLAIM,
                        claim.claimId()
                )).add(
                        safe(matchRepository.sumActiveByTarget(
                                PaymentTargetType.SHIPMENT,
                                claim.shipmentId()
                        ))
                );

        return Optional.of(ClaimOutstandingAmountCalculator.calculate(
                new ru.sber.cargotech.payment.repository.ClaimPaymentData(
                        claim.claimId(),
                        claim.shipmentId(),
                        claim.claimNumber(),
                        claim.debtorInn(),
                        claim.shipmentOrderNumber(),
                        claim.serviceAmount(),
                        claim.calculatedPaidAmount(),
                        claim.remainingPrincipalAmount(),
                        claim.remainingPenaltyAmount(),
                        claim.status()
                ),
                paidAmount
        ));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
