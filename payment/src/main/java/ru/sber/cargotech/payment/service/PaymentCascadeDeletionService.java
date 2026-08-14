package ru.sber.cargotech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.repository.PaymentCheckRepository;
import ru.sber.cargotech.payment.repository.PaymentOutboxWriter;
import ru.sber.cargotech.payment.repository.PaymentRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCascadeDeletionService {

    private static final String CASCADE_REASON =
        "Удаление претензии и связанного рейса";

    private final PaymentRepository paymentRepository;
    private final PaymentCheckRepository paymentCheckRepository;
    private final PaymentOutboxWriter outboxWriter;

    @Transactional
    public int deleteForClaimAndShipment(
        CurrentPaymentUser user,
        UUID claimId,
        UUID shipmentId
    ) {
        List<Payment> payments = paymentRepository.findAllLinkedToClaimOrShipment(
            user.organizationId(),
            PaymentTargetType.CLAIM,
            claimId,
            PaymentTargetType.SHIPMENT,
            shipmentId
        );

        payments.forEach(payment -> {
            paymentRepository.delete(payment);
            outboxWriter.write(
                "PAYMENT",
                payment.getId(),
                "PAYMENT_DELETED",
                user.organizationId(),
                user.userId(),
                Map.of(
                    "paymentId", payment.getId(),
                    "reason", CASCADE_REASON,
                    "claimId", claimId,
                    "shipmentId", shipmentId
                )
            );
        });

        paymentCheckRepository.deleteAllByClaimOrShipment(
            user.organizationId(),
            PaymentTargetType.CLAIM.name(),
            claimId,
            PaymentTargetType.SHIPMENT.name(),
            shipmentId
        );

        log.info(
            "Каскадно удалены платежи претензии и рейса: claimId={}, shipmentId={}, count={}",
            claimId,
            shipmentId,
            payments.size()
        );
        return payments.size();
    }
}
