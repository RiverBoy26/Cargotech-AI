package ru.sber.cargotech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.payment.dto.MarkPaidRequest;
import ru.sber.cargotech.payment.dto.MarkPaidResponse;
import ru.sber.cargotech.payment.dto.PreflightCheckResponse;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentCheck;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentCheckStatus;
import ru.sber.cargotech.payment.enums.PaymentMatchType;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.exception.PaymentException;
import ru.sber.cargotech.payment.repository.ClaimPaymentData;
import ru.sber.cargotech.payment.repository.PaymentCheckRepository;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;
import ru.sber.cargotech.payment.repository.PaymentOutboxWriter;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCheckService {

    private final PaymentServiceImpl paymentService;
    private final PaymentMatchRepository matchRepository;
    private final PaymentCheckRepository checkRepository;
    private final PaymentOutboxWriter outboxWriter;

    @Transactional
    public PreflightCheckResponse preflightCheck(
            UUID claimId,
            String comment,
            CurrentPaymentUser user
    ) {
        log.debug("Preflight-проверка оплаты: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        ClaimPaymentData claim = paymentService.getClaim(
                claimId,
                user.organizationId()
        );

        return createCheck(claim, comment, user, true);
    }

    @Transactional
    public MarkPaidResponse markPaid(
            UUID claimId,
            MarkPaidRequest request,
            CurrentPaymentUser user
    ) {
        log.debug("Подтверждение полной оплаты: claimId={}, paymentId={}, organizationId={}, userId={}", claimId, request.paymentId(), user.organizationId(), user.userId());

        ClaimPaymentData claim = paymentService.getClaim(
                claimId,
                user.organizationId()
        );

        Payment payment = paymentService.getPayment(
                request.paymentId(),
                user.organizationId()
        );

        BigDecimal remaining = claim.serviceAmount()
                .subtract(paymentService.paidAmount(claim))
                .max(BigDecimal.ZERO);

        if (remaining.signum() == 0) {
            throw PaymentException.conflict(
                    "Перевозка по претензии уже полностью оплачена"
            );
        }

        if (payment.getAmount().signum() <= 0) {
            throw PaymentException.unprocessable(
                    "Отрицательный платёж-сторно нельзя использовать для закрытия долга"
            );
        }

        if (paymentService.availableAmount(payment).compareTo(remaining) < 0) {
            throw PaymentException.conflict(
                    "Доступной суммы платежа недостаточно для полного погашения"
            );
        }

        PaymentMatch match = new PaymentMatch();
        match.setPaymentId(payment.getId());
        match.setTargetType(PaymentTargetType.CLAIM);
        match.setTargetId(claim.id());
        match.setMatchedAmount(remaining);
        match.setMatchType(PaymentMatchType.MANUAL);
        match.setConfidence(BigDecimal.ONE);
        match.setActive(true);
        match.setMatchedBy(user.userId());
        match.setMatchedAt(OffsetDateTime.now());

        matchRepository.save(match);

        paymentService.refreshStatus(payment);

        PreflightCheckResponse check = createCheck(
                claim,
                request.comment(),
                user,
                true
        );

        if (check.remainingAmount().signum() > 0) {
            throw PaymentException.conflict(
                    "После сопоставления остался непогашенный остаток"
            );
        }

        UUID eventId = outboxWriter.write(
                "CLAIM",
                claim.id(),
                "CLAIM_PAYMENT_CONFIRMED",
                user.organizationId(),
                user.userId(),
                Map.of(
                        "paymentId", payment.getId(),
                        "paymentCheckId", check.checkId(),
                        "paidAmount", check.paidAmount(),
                        "remainingAmount", check.remainingAmount()
                )
        );

        return new MarkPaidResponse(
                claim.id(),
                payment.getId(),
                check.checkId(),
                eventId,
                check.paidAmount(),
                check.remainingAmount(),
                check.paymentStatus()
        );
    }

    private PreflightCheckResponse createCheck(
            ClaimPaymentData claim,
            String comment,
            CurrentPaymentUser user,
            boolean publishEvent
    ) {
        log.debug("Формирование проверки оплаты: claimId={}, serviceAmount={}, publishEvent={}, userId={}", claim.id(), claim.serviceAmount(), publishEvent, user.userId());

        BigDecimal paid = paymentService.paidAmount(claim);

        BigDecimal remaining = claim.serviceAmount()
                .subtract(paid)
                .max(BigDecimal.ZERO);

        PaymentCheckStatus status = paymentService.resolvePaymentStatus(
                claim.serviceAmount(),
                paid
        );

        PaymentCheck check = new PaymentCheck();
        check.setOrganizationId(user.organizationId());
        check.setTargetType("CLAIM");
        check.setTargetId(claim.id());
        check.setServiceAmount(claim.serviceAmount());
        check.setPaidAmount(paid);
        check.setRemainingAmount(remaining);
        check.setPaymentStatus(status);
        check.setSource("PAYMENT_MVP");
        check.setMatchedPaymentIds(paymentService.paymentIds(claim));
        check.setCheckedBy(user.userId());
        check.setCheckedAt(OffsetDateTime.now());
        check.setComment(comment);

        check = checkRepository.save(check);

        paymentService.updateLastPaymentCheck(
                claim.id(),
                user.organizationId(),
                check.getId()
        );

        if (publishEvent) {
            outboxWriter.write(
                    "CLAIM",
                    claim.id(),
                    "PAYMENT_CHECKED",
                    user.organizationId(),
                    user.userId(),
                    Map.of(
                            "paymentCheckId", check.getId(),
                            "paymentStatus", status.name(),
                            "paidAmount", paid,
                            "remainingAmount", remaining
                    )
            );
        }

        boolean canSend = remaining.signum() > 0;

        return new PreflightCheckResponse(
                check.getId(),
                claim.id(),
                claim.shipmentId(),
                claim.serviceAmount(),
                paid,
                remaining,
                status,
                paymentService.lastPaymentDate(claim, user.organizationId()),
                paymentService.paymentIds(claim),
                canSend,
                canSend ? "ALLOW_SEND" : "BLOCK_SEND_AND_MARK_PAID",
                check.getCheckedAt()
        );
    }
}