package ru.sber.cargotech.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.sber.cargotech.payment.enums.PaymentMatchType;
import ru.sber.cargotech.payment.enums.PaymentTargetType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_matches", schema = "cargotech")
public class PaymentMatch {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private PaymentTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "matched_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal matchedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false)
    private PaymentMatchType matchType;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "matched_by")
    private UUID matchedBy;

    @Column(name = "matched_at", nullable = false)
    private OffsetDateTime matchedAt;

    @Column(name = "unmatched_by")
    private UUID unmatchedBy;

    @Column(name = "unmatched_at")
    private OffsetDateTime unmatchedAt;

    @Column(name = "unmatch_reason")
    private String unmatchReason;
}
