package ru.sber.cargotech.claim.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.sber.cargotech.claim.enums.PenaltyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "claim_calculations", schema = "cargotech")
public class ClaimCalculation {
    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "calculation_version", nullable = false)
    private Integer calculationVersion;

    @Column(name = "principal_debt", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalDebt;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "remaining_debt", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingDebt;

    @Column(name = "overdue_start_date")
    private LocalDate overdueStartDate;

    @Column(name = "calculation_date", nullable = false)
    private LocalDate calculationDate;

    @Column(name = "overdue_days", nullable = false)
    private Integer overdueDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false, length = 64)
    private PenaltyType penaltyType;

    @Column(name = "penalty_rate", precision = 12, scale = 6)
    private BigDecimal penaltyRate;

    @Column(name = "penalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal penaltyAmount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    private String formula;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputSnapshot = Map.of();

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
