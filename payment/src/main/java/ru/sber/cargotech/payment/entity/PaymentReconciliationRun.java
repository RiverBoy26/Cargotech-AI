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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.sber.cargotech.payment.enums.ReconciliationStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_reconciliation_runs", schema = "cargotech")
public class PaymentReconciliationRun {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReconciliationStatus status;

    @Column(name = "payments_checked", nullable = false)
    private int paymentsChecked;

    @Column(name = "matches_created", nullable = false)
    private int matchesCreated;

    @Column(name = "unmatched_count", nullable = false)
    private int unmatchedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", columnDefinition = "jsonb")
    private Map<String, Object> parameters;

    @Column(name = "started_by", nullable = false)
    private UUID startedBy;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "error_message")
    private String errorMessage;
}
