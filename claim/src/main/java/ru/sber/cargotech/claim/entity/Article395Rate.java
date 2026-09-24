package ru.sber.cargotech.claim.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "article_395_rates", schema = "cargotech")
public class Article395Rate {
    @Id
    private UUID id;

    @Column(name = "effective_from", nullable = false, unique = true)
    private LocalDate effectiveFrom;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal rate;

    @Column(nullable = false, length = 500)
    private String source;

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
