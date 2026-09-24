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
import ru.sber.cargotech.claim.enums.ClauseType;
import ru.sber.cargotech.claim.enums.ContractExtractionField;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "claim_contract_extractions", schema = "cargotech")
public class ContractExtractedValue {
    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_name", nullable = false, length = 64)
    private ContractExtractionField field;

    @Column(name = "extracted_value")
    private String value;

    @Column(name = "source_text")
    private String source;

    @Column(name = "source_page")
    private Integer sourcePage;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "clause_number", length = 64)
    private String clauseNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "clause_type", length = 64)
    private ClauseType clauseType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "manually_edited", nullable = false)
    private boolean manuallyEdited;

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
