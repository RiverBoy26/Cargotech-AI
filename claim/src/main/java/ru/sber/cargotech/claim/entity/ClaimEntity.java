package ru.sber.cargotech.claim.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "claim_claims", schema = "cargotech")
public class ClaimEntity {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "claim_number", nullable = false, length = 128)
    private String claimNumber;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "creditor_id", nullable = false)
    private UUID creditorId;

    @Column(name = "debtor_id", nullable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 64)
    private ClaimType claimType = ClaimType.PAYMENT_DELAY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private ClaimStatus status = ClaimStatus.DRAFT;

    private String reason;

    @Column(name = "principal_debt", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalDebt = BigDecimal.ZERO;

    @Column(name = "penalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal penaltyAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "non_payment_confirmed", nullable = false)
    private boolean nonPaymentConfirmed = false;

    @Column(name = "non_payment_confirmed_at")
    private OffsetDateTime nonPaymentConfirmedAt;

    @Column(name = "non_payment_confirmed_by")
    private UUID nonPaymentConfirmedBy;

    @Column(name = "non_payment_confirmation_comment")
    private String nonPaymentConfirmationComment;

    @Column(name = "last_payment_check_id")
    private UUID lastPaymentCheckId;

    @Column(name = "assigned_lawyer_id")
    private UUID assignedLawyerId;

    @Column(name = "final_version_id")
    private UUID finalVersionId;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancellation_reason_code", length = 64)
    private String cancellationReasonCode;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "escalated_at")
    private OffsetDateTime escalatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private Long version = 0L;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        normalizeTotals();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
        normalizeTotals();
    }

    public void normalizeTotals() {
        if (principalDebt == null) {
            principalDebt = BigDecimal.ZERO;
        }
        if (penaltyAmount == null) {
            penaltyAmount = BigDecimal.ZERO;
        }
        totalAmount = principalDebt.add(penaltyAmount);
    }
}
