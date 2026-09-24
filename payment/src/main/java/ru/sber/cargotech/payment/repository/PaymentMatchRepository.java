package ru.sber.cargotech.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentMatchRepository
    extends JpaRepository<PaymentMatch, UUID> {

    List<PaymentMatch> findAllByPaymentIdOrderByMatchedAtAsc(UUID paymentId);

    @Query("""
        select distinct match.targetId
        from PaymentMatch match
        where match.paymentId = :paymentId
          and match.targetType = :targetType
          and match.active = true
        """)
    List<UUID> findActiveTargetIdsByPaymentIdAndType(
        UUID paymentId,
        PaymentTargetType targetType
    );

    Optional<PaymentMatch> findByIdAndPaymentId(UUID id, UUID paymentId);

    @Query("""
        select coalesce(sum(match.matchedAmount), 0)
        from PaymentMatch match
        where match.paymentId = :paymentId
          and match.active = true
        """)
    BigDecimal sumActiveByPaymentId(UUID paymentId);

    @Query("""
        select coalesce(sum(match.matchedAmount), 0)
        from PaymentMatch match
        where match.targetType = :targetType
          and match.targetId = :targetId
          and match.active = true
        """)
    BigDecimal sumActiveByTarget(
        PaymentTargetType targetType,
        UUID targetId
    );

    @Query("""
        select distinct match.paymentId
        from PaymentMatch match
        where match.targetType = :targetType
          and match.targetId = :targetId
          and match.active = true
        """)
    List<UUID> findPaymentIdsByTarget(
        PaymentTargetType targetType,
        UUID targetId
    );

    @Query("""
        select payment.paymentDate as paymentDate,
               sum(match.matchedAmount) as matchedAmount
        from PaymentMatch match
        join Payment payment on payment.id = match.paymentId
        where match.active = true
          and (
            (match.targetType = :claimType and match.targetId = :claimId)
            or
            (match.targetType = :shipmentType and match.targetId = :shipmentId)
          )
        group by payment.paymentDate
        order by payment.paymentDate
        """)
    List<PaymentAllocationView> findActiveAllocationsByTargets(
        PaymentTargetType claimType,
        UUID claimId,
        PaymentTargetType shipmentType,
        UUID shipmentId
    );

    interface PaymentAllocationView {
        LocalDate getPaymentDate();

        BigDecimal getMatchedAmount();
    }
}
