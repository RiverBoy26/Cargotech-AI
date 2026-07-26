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
import ru.sber.cargotech.claim.enums.ClaimStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "claim_status_history", schema = "cargotech")
public class ClaimStatusHistory {
    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 64)
    private ClaimStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 64)
    private ClaimStatus newStatus;

    private String reason;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (changedAt == null) {
            changedAt = OffsetDateTime.now();
        }
    }
}
