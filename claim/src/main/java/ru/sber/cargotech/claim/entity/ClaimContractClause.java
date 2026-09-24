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
import ru.sber.cargotech.claim.enums.ClauseType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "claim_contract_clauses", schema = "cargotech")
public class ClaimContractClause {
    @Id
    private UUID id;
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;
    @Column(name = "clause_number", length = 64)
    private String clauseNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "clause_type", nullable = false, length = 64)
    private ClauseType clauseType;
    @Column(name = "section_name")
    private String sectionName;
    @Column(nullable = false)
    private String text;
    @Column(name = "source_page")
    private Integer sourcePage;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "extracted", nullable = false)
    private boolean extracted;
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
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }
}
