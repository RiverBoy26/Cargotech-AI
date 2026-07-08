package ru.sber.cargotech.payment.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.payment.dto.ClaimPaymentsResponse;
import ru.sber.cargotech.payment.dto.PaymentDetailsResponse;
import ru.sber.cargotech.payment.dto.PaymentMatchResponse;
import ru.sber.cargotech.payment.dto.PaymentResponse;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentCheckStatus;
import ru.sber.cargotech.payment.enums.PaymentStatus;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.exception.PaymentException;
import ru.sber.cargotech.payment.repository.ClaimPaymentData;
import ru.sber.cargotech.payment.repository.ClaimPaymentRepository;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;
import ru.sber.cargotech.payment.repository.PaymentRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMatchRepository matchRepository;
    private final ClaimPaymentRepository claimRepository;

    public PaymentServiceImpl(
        PaymentRepository paymentRepository,
        PaymentMatchRepository matchRepository,
        ClaimPaymentRepository claimRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.matchRepository = matchRepository;
        this.claimRepository = claimRepository;
    }

    public Page<PaymentResponse> findAll(
        Pageable pageable,
        CurrentPaymentUser user
    ) {
        return paymentRepository
            .findAllByOrganizationId(user.organizationId(), pageable)
            .map(this::toResponse);
    }

    public PaymentDetailsResponse getDetails(
        UUID paymentId,
        CurrentPaymentUser user
    ) {
        Payment payment = getPayment(paymentId, user.organizationId());
        return new PaymentDetailsResponse(
            toResponse(payment),
            matchRepository.findAllByPaymentIdOrderByMatchedAtAsc(paymentId)
                .stream()
                .map(this::toMatchResponse)
                .toList()
        );
    }

    public ClaimPaymentsResponse findByClaim(
        UUID claimId,
        CurrentPaymentUser user
    ) {
        ClaimPaymentData claim = getClaim(claimId, user.organizationId());
        List<UUID> paymentIds = paymentIds(claim);
        List<PaymentResponse> payments = paymentIds.stream()
            .map(id -> getPayment(id, user.organizationId()))
            .map(this::toResponse)
            .toList();

        BigDecimal paid = paidAmount(claim);
        BigDecimal remaining = claim.serviceAmount()
            .subtract(paid)
            .max(BigDecimal.ZERO);

        return new ClaimPaymentsResponse(
            claim.id(),
            claim.shipmentId(),
            claim.claimNumber(),
            claim.serviceAmount(),
            paid,
            remaining,
            resolvePaymentStatus(claim.serviceAmount(), paid),
            lastPaymentDate(claim, user.organizationId()),
            payments
        );
    }

    public Payment getPayment(UUID paymentId, UUID organizationId) {
        return paymentRepository
            .findByIdAndOrganizationId(paymentId, organizationId)
            .orElseThrow(() -> PaymentException.notFound(
                "Платёж %s не найден".formatted(paymentId)
            ));
    }

    public ClaimPaymentData getClaim(
        UUID claimId,
        UUID organizationId
    ) {
        return claimRepository
            .findByIdAndOrganizationId(claimId, organizationId)
            .orElseThrow(() -> PaymentException.notFound(
                "Претензия %s не найдена".formatted(claimId)
            ));
    }

    public BigDecimal matchedAmount(UUID paymentId) {
        return matchRepository.sumActiveByPaymentId(paymentId);
    }

    public BigDecimal availableAmount(Payment payment) {
        return payment.getAmount().abs()
            .subtract(matchedAmount(payment.getId()))
            .max(BigDecimal.ZERO);
    }

    public BigDecimal paidAmount(ClaimPaymentData claim) {
        return matchRepository.sumActiveByTarget(
            PaymentTargetType.CLAIM,
            claim.id()
        ).add(matchRepository.sumActiveByTarget(
            PaymentTargetType.SHIPMENT,
            claim.shipmentId()
        ));
    }

    public List<UUID> paymentIds(ClaimPaymentData claim) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        ids.addAll(matchRepository.findPaymentIdsByTarget(
            PaymentTargetType.CLAIM,
            claim.id()
        ));
        ids.addAll(matchRepository.findPaymentIdsByTarget(
            PaymentTargetType.SHIPMENT,
            claim.shipmentId()
        ));
        return List.copyOf(ids);
    }

    public LocalDate lastPaymentDate(
        ClaimPaymentData claim,
        UUID organizationId
    ) {
        return paymentRepository.findLastPaymentDateForClaim(
            organizationId,
            PaymentTargetType.CLAIM,
            claim.id(),
            PaymentTargetType.SHIPMENT,
            claim.shipmentId()
        );
    }

    public PaymentCheckStatus resolvePaymentStatus(
        BigDecimal expectedAmount,
        BigDecimal paidAmount
    ) {
        if (paidAmount.signum() == 0) {
            return PaymentCheckStatus.NOT_PAID;
        }
        int comparison = paidAmount.compareTo(expectedAmount);
        if (comparison < 0) {
            return PaymentCheckStatus.PARTIALLY_PAID;
        }
        if (comparison == 0) {
            return PaymentCheckStatus.FULLY_PAID;
        }
        return PaymentCheckStatus.OVERPAID;
    }

    @Transactional
    public void refreshStatus(Payment payment) {
        BigDecimal matched = matchedAmount(payment.getId());
        if (matched.signum() == 0) {
            payment.setStatus(PaymentStatus.IMPORTED);
        } else if (matched.compareTo(payment.getAmount().abs()) < 0) {
            payment.setStatus(PaymentStatus.PARTIALLY_MATCHED);
        } else {
            payment.setStatus(PaymentStatus.MATCHED);
        }
        paymentRepository.save(payment);
    }

    public PaymentResponse toResponse(Payment payment) {
        BigDecimal matched = matchedAmount(payment.getId());
        return new PaymentResponse(
            payment.getId(),
            payment.getSourceSystem(),
            payment.getExternalPaymentId(),
            payment.getPaymentNumber(),
            payment.getPaymentDate(),
            payment.getPayerInn(),
            payment.getPayerName(),
            payment.getRecipientInn(),
            payment.getRecipientName(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getPurpose(),
            payment.getStatus(),
            matched,
            payment.getAmount().abs().subtract(matched).max(BigDecimal.ZERO)
        );
    }

    public PaymentMatchResponse toMatchResponse(PaymentMatch match) {
        return new PaymentMatchResponse(
            match.getId(),
            match.getPaymentId(),
            match.getTargetType(),
            match.getTargetId(),
            match.getMatchedAmount(),
            match.getMatchType(),
            match.getConfidence(),
            match.isActive(),
            match.getMatchedBy(),
            match.getMatchedAt(),
            match.getUnmatchedBy(),
            match.getUnmatchedAt(),
            match.getUnmatchReason()
        );
    }
}
