package ru.sber.cargotech.payment.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.sber.cargotech.payment.dto.ClaimPaymentsResponse;
import ru.sber.cargotech.payment.dto.PaymentDetailsResponse;
import ru.sber.cargotech.payment.dto.PaymentMatchResponse;
import ru.sber.cargotech.payment.dto.PaymentResponse;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentCheckStatus;
import ru.sber.cargotech.payment.repository.ClaimPaymentData;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PaymentService {
    Page<PaymentResponse> findAll(
            Pageable pageable,
            CurrentPaymentUser user
    );

    PaymentDetailsResponse getDetails(
            UUID paymentId,
            CurrentPaymentUser user
    );

    ClaimPaymentsResponse findByClaim(
            UUID claimId,
            CurrentPaymentUser user
    );

    Payment getPayment(UUID paymentId, UUID organizationId);

    ClaimPaymentData getClaim(
            UUID claimId,
            UUID organizationId
    );

    BigDecimal matchedAmount(UUID paymentId);

    BigDecimal availableAmount(Payment payment);

    BigDecimal paidAmount(ClaimPaymentData claim);

    List<UUID> paymentIds(ClaimPaymentData claim);

    LocalDate lastPaymentDate(
            ClaimPaymentData claim,
            UUID organizationId
    );

    PaymentCheckStatus resolvePaymentStatus(
            BigDecimal expectedAmount,
            BigDecimal paidAmount
    );

    void refreshStatus(Payment payment);

    PaymentResponse toResponse(Payment payment);

    PaymentMatchResponse toMatchResponse(PaymentMatch match);
}
