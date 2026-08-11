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
import ru.sber.cargotech.payment.enums.PaymentSourceSystem;
import ru.sber.cargotech.payment.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_payments", schema = "cargotech")
public class Payment {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "import_id")
    private UUID importId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false)
    private PaymentSourceSystem sourceSystem;

    @Column(name = "external_payment_id")
    private String externalPaymentId;

    @Column(name = "payment_number")
    private String paymentNumber;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "payer_inn")
    private String payerInn;

    @Column(name = "payer_name")
    private String payerName;

    @Column(name = "recipient_inn")
    private String recipientInn;

    @Column(name = "recipient_name")
    private String recipientName;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "purpose")
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", columnDefinition = "jsonb")
    private Map<String, Object> rawData;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
