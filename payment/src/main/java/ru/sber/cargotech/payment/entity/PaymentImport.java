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
import ru.sber.cargotech.payment.enums.PaymentImportStatus;
import ru.sber.cargotech.payment.enums.PaymentSourceSystem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_imports", schema = "cargotech")
public class PaymentImport {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private PaymentSourceSystem sourceSystem;

    @Column(name = "source_file_id")
    private UUID sourceFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentImportStatus status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "imported_rows", nullable = false)
    private int importedRows;

    @Column(name = "failed_rows", nullable = false)
    private int failedRows;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_details", columnDefinition = "jsonb")
    private List<Map<String, Object>> errorDetails;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
