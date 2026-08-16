package ru.sber.cargotech.payment.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.enums.PaymentSourceSystem;
import ru.sber.cargotech.payment.enums.PaymentStatus;
import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Page<Payment> findAllByOrganizationId(
        UUID organizationId,
        Pageable pageable
    );

    Optional<Payment> findByIdAndOrganizationId(
        UUID id,
        UUID organizationId
    );

    @Query("""
        select distinct payment
        from Payment payment
        where payment.organizationId = :organizationId
          and payment.id in (
              select match.paymentId
              from PaymentMatch match
              where (match.targetType = :claimType and match.targetId = :claimId)
                 or (match.targetType = :shipmentType and match.targetId = :shipmentId)
          )
        """)
    List<Payment> findAllLinkedToClaimOrShipment(
        UUID organizationId,
        PaymentTargetType claimType,
        UUID claimId,
        PaymentTargetType shipmentType,
        UUID shipmentId
    );

    List<Payment> findAllByOrganizationIdAndStatusOrderByPaymentDateAsc(
        UUID organizationId,
        PaymentStatus status
    );

    boolean existsByOrganizationIdAndSourceSystemAndExternalPaymentId(
        UUID organizationId,
        PaymentSourceSystem sourceSystem,
        String externalPaymentId
    );

    @Query("""
        select max(payment.paymentDate)
        from Payment payment
        where payment.organizationId = :organizationId
          and payment.id in (
              select match.paymentId
              from PaymentMatch match
              where match.active = true
                and (
                    (match.targetType = :claimType and match.targetId = :claimId)
                    or
                    (match.targetType = :shipmentType and match.targetId = :shipmentId)
                )
          )
        """)
    LocalDate findLastPaymentDateForClaim(
        UUID organizationId,
        PaymentTargetType claimType,
        UUID claimId,
        PaymentTargetType shipmentType,
        UUID shipmentId
    );
}
