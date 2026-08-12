package ru.sber.cargotech.claim.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.enums.TermDayType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "claim_contracts", schema = "cargotech")
public class ClaimContract {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(length = 128)
    private String number;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "expeditor_id", nullable = false)
    private UUID expeditorId;

    @Column(name = "signed_at")
    private LocalDate signedAt;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContractStatus status = ContractStatus.ACTIVE;

    @Column(name = "payment_days")
    private Integer paymentDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_day_type", length = 32)
    private TermDayType paymentDayType;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_start_event", length = 64)
    private PaymentStartEvent paymentStartEvent;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", length = 64)
    private PenaltyType penaltyType;

    @Column(name = "penalty_rate", precision = 12, scale = 6)
    private BigDecimal penaltyRate;

    @Column(name = "claim_response_days")
    private Integer claimResponseDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_response_day_type", length = 32)
    private TermDayType claimResponseDayType;

    private String jurisdiction;

    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false, length = 32)
    private ContractExtractionStatus extractionStatus = ContractExtractionStatus.NOT_STARTED;

    @Column(name = "extraction_confirmed_at")
    private OffsetDateTime extractionConfirmedAt;

    @Column(name = "extraction_confirmed_by")
    private UUID extractionConfirmedBy;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
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
    }
}
