package ru.sber.cargotech.claim.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentPreflightResponse {
    private UUID checkId;
    private UUID claimId;
    private UUID shipmentId;
    private BigDecimal serviceAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private String paymentStatus;
    private boolean canSend;
    private String recommendedAction;
    private OffsetDateTime checkedAt;
}
