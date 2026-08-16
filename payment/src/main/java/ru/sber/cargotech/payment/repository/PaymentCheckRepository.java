package ru.sber.cargotech.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.sber.cargotech.payment.entity.PaymentCheck;

import java.util.UUID;

public interface PaymentCheckRepository
    extends JpaRepository<PaymentCheck, UUID> {

    @Modifying
    @Query("""
        delete from PaymentCheck paymentCheck
        where paymentCheck.organizationId = :organizationId
          and ((paymentCheck.targetType = :claimType and paymentCheck.targetId = :claimId)
            or (paymentCheck.targetType = :shipmentType and paymentCheck.targetId = :shipmentId))
        """)
    int deleteAllByClaimOrShipment(
        UUID organizationId,
        String claimType,
        UUID claimId,
        String shipmentType,
        UUID shipmentId
    );
}
