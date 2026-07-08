package ru.sber.cargotech.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.payment.dto.CreatePaymentMatchRequest;
import ru.sber.cargotech.payment.dto.PaymentDetailsResponse;
import ru.sber.cargotech.payment.dto.PaymentMatchResponse;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentMatchType;
import ru.sber.cargotech.payment.exception.PaymentException;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;
import ru.sber.cargotech.payment.repository.PaymentOutboxWriter;
import ru.sber.cargotech.payment.repository.PaymentTargetRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentMatchingService {

    private final PaymentServiceImpl paymentService;
    private final PaymentMatchRepository matchRepository;
    private final PaymentTargetRepository targetRepository;
    private final PaymentOutboxWriter outboxWriter;

    public PaymentMatchingService(
        PaymentServiceImpl paymentService,
        PaymentMatchRepository matchRepository,
        PaymentTargetRepository targetRepository,
        PaymentOutboxWriter outboxWriter
    ) {
        this.paymentService = paymentService;
        this.matchRepository = matchRepository;
        this.targetRepository = targetRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public PaymentMatchResponse match(
        UUID paymentId,
        CreatePaymentMatchRequest request,
        CurrentPaymentUser user
    ) {
        Payment payment = paymentService.getPayment(
            paymentId,
            user.organizationId()
        );

        if (payment.getAmount().signum() <= 0) {
            throw PaymentException.unprocessable(
                "Отрицательный платёж-сторно нельзя распределять как оплату"
            );
        }

        if (!targetRepository.exists(
            user.organizationId(),
            request.targetType(),
            request.targetId()
        )) {
            throw PaymentException.notFound(
                "Цель сопоставления не найдена"
            );
        }

        BigDecimal available = paymentService.availableAmount(payment);
        if (request.matchedAmount().compareTo(available) > 0) {
            throw PaymentException.conflict(
                "Сумма сопоставления %s превышает доступный остаток %s"
                    .formatted(request.matchedAmount(), available)
            );
        }

        targetRepository.remainingAmount(
            user.organizationId(),
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

        return paymentService.toMatchResponse(match);
    }

    @Transactional
    public PaymentDetailsResponse unmatch(
        UUID paymentId,
        UUID matchId,
        String reason,
        CurrentPaymentUser user
    ) {
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

        return paymentService.getDetails(paymentId, user);
    }
}
