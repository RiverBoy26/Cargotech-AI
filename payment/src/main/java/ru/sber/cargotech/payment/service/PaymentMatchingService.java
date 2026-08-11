package ru.sber.cargotech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.payment.dto.CreatePaymentMatchRequest;
import ru.sber.cargotech.payment.dto.PaymentDetailsResponse;
import ru.sber.cargotech.payment.dto.PaymentMatchResponse;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentMatchType;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.exception.PaymentException;
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
public class PaymentMatchingService {

    private final PaymentServiceImpl paymentService;
    private final PaymentTargetService targetService;
    private final PaymentMatchRepository matchRepository;
    private final PaymentOutboxWriter outboxWriter;
    private final ClaimPaymentSynchronizationService claimSynchronizationService;

    @Transactional
    public PaymentMatchResponse match(
        UUID paymentId,
        CreatePaymentMatchRequest request,
        CurrentPaymentUser user
    ) {
        log.debug("Ручное сопоставление: paymentId={}, organizationId={}, userId={}, targetType={}, targetId={}, requestedAmount={}", paymentId, user.organizationId(), user.userId(), request.targetType(), request.targetId(), request.matchedAmount());

        Payment payment = paymentService.getPayment(
            paymentId,
            user.organizationId()
        );

        if (payment.getAmount().signum() <= 0) {
            throw PaymentException.unprocessable(
                "Отрицательный платёж-сторно нельзя распределять как оплату"
            );
        }

        if (!targetService.exists(
                request.targetType(),
                request.targetId()
        )) {
            throw PaymentException.notFound(
                "Цель сопоставления не найдена"
            );
        }

        BigDecimal available = paymentService.availableAmount(payment);
        log.debug("Доступная сумма платежа: paymentId={}, availableAmount={}", paymentId, available);

        if (request.matchedAmount().compareTo(available) > 0) {
            throw PaymentException.conflict(
                "Сумма сопоставления %s превышает доступный остаток %s"
                    .formatted(request.matchedAmount(), available)
            );
        }

        targetService.remainingAmount(
                request.targetType(),
                request.targetId()
        ).ifPresent(remaining -> {
            if (request.matchedAmount().compareTo(remaining) > 0) {
                throw PaymentException.conflict(
                    "Сумма сопоставления %s превышает остаток цели %s"
                        .formatted(request.matchedAmount(), remaining)
                );
            }
        });

        PaymentMatch match = new PaymentMatch();
        match.setPaymentId(paymentId);
        match.setTargetType(request.targetType());
        match.setTargetId(request.targetId());
        match.setMatchedAmount(request.matchedAmount());
        match.setMatchType(PaymentMatchType.MANUAL);
        match.setConfidence(BigDecimal.ONE);
        match.setActive(true);
        match.setMatchedBy(user.userId());
        match.setMatchedAt(OffsetDateTime.now());
        match = matchRepository.save(match);

        paymentService.refreshStatus(payment);

        outboxWriter.write(
            "PAYMENT",
            paymentId,
            "PAYMENT_MATCHED",
            user.organizationId(),
            user.userId(),
            Map.of(
                "matchId", match.getId(),
                "targetType", request.targetType().name(),
                "targetId", request.targetId(),
                "matchedAmount", request.matchedAmount(),
                "comment", request.comment() == null ? "" : request.comment()
            )
        );

        if (request.targetType() == PaymentTargetType.CLAIM) {
            claimSynchronizationService.synchronizeAfterCommit(request.targetId());
        }

        return paymentService.toMatchResponse(match);
    }

    @Transactional
    public PaymentDetailsResponse unmatch(
        UUID paymentId,
        UUID matchId,
        String reason,
        CurrentPaymentUser user
    ) {
        log.debug("Отмена сопоставления: paymentId={}, matchId={}, organizationId={}, userId={}", paymentId, matchId, user.organizationId(), user.userId());

        if (reason == null || reason.isBlank()) {
            throw PaymentException.unprocessable(
                "Причина отмены сопоставления обязательна"
            );
        }

        Payment payment = paymentService.getPayment(
            paymentId,
            user.organizationId()
        );
        PaymentMatch match = matchRepository
            .findByIdAndPaymentId(matchId, paymentId)
            .orElseThrow(() -> PaymentException.notFound(
                "Сопоставление %s не найдено".formatted(matchId)
            ));

        if (!match.isActive()) {
            throw PaymentException.conflict(
                "Сопоставление уже отменено"
            );
        }

        match.setActive(false);
        match.setUnmatchedBy(user.userId());
        match.setUnmatchedAt(OffsetDateTime.now());
        match.setUnmatchReason(reason);
        matchRepository.save(match);
        paymentService.refreshStatus(payment);

        outboxWriter.write(
            "PAYMENT",
            paymentId,
            "PAYMENT_UNMATCHED",
            user.organizationId(),
            user.userId(),
            Map.of(
                "matchId", matchId,
                "reason", reason
            )
        );

        if (match.getTargetType() == PaymentTargetType.CLAIM) {
            claimSynchronizationService.synchronizeAfterCommit(match.getTargetId());
        }

        return paymentService.getDetails(paymentId, user);
    }
}
