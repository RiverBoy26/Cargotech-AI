package ru.sber.cargotech.payment.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.repository.PaymentCheckRepository;
import ru.sber.cargotech.payment.repository.PaymentOutboxWriter;
import ru.sber.cargotech.payment.repository.PaymentRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCascadeDeletionServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentCheckRepository paymentCheckRepository;
    @Mock private PaymentOutboxWriter outboxWriter;

    @Test
    void deletesEveryPaymentLinkedToClaimOrShipmentAndItsChecks() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        Payment first = payment(UUID.randomUUID());
        Payment second = payment(UUID.randomUUID());

        when(paymentRepository.findAllLinkedToClaimOrShipment(
            organizationId,
            PaymentTargetType.CLAIM,
            claimId,
            PaymentTargetType.SHIPMENT,
            shipmentId
        )).thenReturn(List.of(first, second));

        PaymentCascadeDeletionService service = new PaymentCascadeDeletionService(
            paymentRepository,
            paymentCheckRepository,
            outboxWriter
        );

        int deleted = service.deleteForClaimAndShipment(
            new CurrentPaymentUser(userId, organizationId),
            claimId,
            shipmentId
        );

        assertThat(deleted).isEqualTo(2);
        InOrder order = inOrder(paymentRepository, outboxWriter);
        order.verify(paymentRepository).delete(first);
        order.verify(outboxWriter).write(
            org.mockito.ArgumentMatchers.eq("PAYMENT"),
            org.mockito.ArgumentMatchers.eq(first.getId()),
            org.mockito.ArgumentMatchers.eq("PAYMENT_DELETED"),
            org.mockito.ArgumentMatchers.eq(organizationId),
            org.mockito.ArgumentMatchers.eq(userId),
            org.mockito.ArgumentMatchers.anyMap()
        );
        order.verify(paymentRepository).delete(second);
        verify(paymentCheckRepository).deleteAllByClaimOrShipment(
            organizationId,
            "CLAIM",
            claimId,
            "SHIPMENT",
            shipmentId
        );
    }

    private static Payment payment(UUID id) {
        Payment payment = new Payment();
        payment.setId(id);
        return payment;
    }
}
