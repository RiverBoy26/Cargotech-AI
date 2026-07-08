package ru.sber.cargotech.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentMatchRepository
    extends JpaRepository<PaymentMatch, UUID> {

    List<PaymentMatch> findAllByPaymentIdOrderByMatchedAtAsc(UUID paymentId);

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
}
