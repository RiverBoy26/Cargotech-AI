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
import ru.sber.cargotech.claim.enums.ShipmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "claim_shipments", schema = "cargotech")
public class ClaimShipment {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "order_number", nullable = false, length = 128)
    private String orderNumber;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "expeditor_id", nullable = false)
    private UUID expeditorId;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "route_from", length = 500)
    private String routeFrom;

    @Column(name = "route_to", length = 500)
    private String routeTo;

    @Column(name = "loading_date")
    private LocalDate loadingDate;

    @Column(name = "unloading_date")
    private LocalDate unloadingDate;

    @Column(name = "act_signed_at")
    private LocalDate actSignedAt;

    @Column(name = "ttn_signed_at")
    private LocalDate ttnSignedAt;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "payment_start_event_date")
    private LocalDate paymentStartEventDate;

    @Column(name = "service_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal serviceAmount;

    @Column(nullable = false, length = 10)
    private String currency = "RUB";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ShipmentStatus status = ShipmentStatus.CREATED;

    @Column(name = "external_id", length = 255)
    private String externalId;

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
