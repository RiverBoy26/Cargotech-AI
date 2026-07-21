package ru.sber.cargotech.claim.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentClaimStateResponse {
    private UUID claimId;
    private UUID shipmentId;
    private String claimNumber;
    private BigDecimal serviceAmount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private String paymentStatus;
    private LocalDate lastPaymentDate;
}
