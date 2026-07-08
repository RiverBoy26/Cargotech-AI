package ru.sber.cargotech.payment.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.payment.dto.ReconciliationResponse;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.entity.PaymentReconciliationRun;
import ru.sber.cargotech.payment.enums.PaymentMatchType;
import ru.sber.cargotech.payment.enums.PaymentStatus;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.enums.ReconciliationStatus;
import ru.sber.cargotech.payment.exception.PaymentException;
import ru.sber.cargotech.payment.repository.ClaimPaymentData;
import ru.sber.cargotech.payment.repository.ClaimPaymentRepository;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;
import ru.sber.cargotech.payment.repository.PaymentOutboxWriter;
import ru.sber.cargotech.payment.repository.PaymentReconciliationRunRepository;
import ru.sber.cargotech.payment.repository.PaymentRepository;
import ru.sber.cargotech.payment.repository.PaymentTargetCandidate;
import ru.sber.cargotech.payment.repository.PaymentTargetRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentReconciliationService {

    private final PaymentRepository paymentRepository;
    private final PaymentMatchRepository matchRepository;
    private final ClaimPaymentRepository claimRepository;
    private final PaymentTargetRepository targetRepository;
    private final PaymentReconciliationRunRepository runRepository;
    private final PaymentServiceImpl paymentService;
    private final PaymentOutboxWriter outboxWriter;

    public PaymentReconciliationService(
        PaymentRepository paymentRepository,
        PaymentMatchRepository matchRepository,
        ClaimPaymentRepository claimRepository,
        PaymentTargetRepository targetRepository,
        PaymentReconciliationRunRepository runRepository,
        PaymentServiceImpl paymentService,
        PaymentOutboxWriter outboxWriter
    ) {
        this.paymentRepository = paymentRepository;
        this.matchRepository = matchRepository;
        this.claimRepository = claimRepository;
        this.targetRepository = targetRepository;
        this.runRepository = runRepository;
        this.paymentService = paymentService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public ReconciliationResponse reconcile(CurrentPaymentUser user) {
        PaymentReconciliationRun run = startRun(user);
        List<Payment> payments = paymentRepository
            .findAllByOrganizationIdAndStatusOrderByPaymentDateAsc(
                user.organizationId(),
                PaymentStatus.IMPORTED
            );

        int matchesCreated = 0;
        try {
            for (Payment payment : payments) {
                Optional<PaymentTargetCandidate> candidate = findCandidate(
                    payment,
                    user.organizationId()
                );
                if (candidate.isPresent()
                    && createAutomaticMatch(
                        payment,
                        candidate.get(),
                        user.userId()
                    )) {
                    matchesCreated++;
                }
            }

            completeRun(run, payments.size(), matchesCreated);
            outboxWriter.write(
                "PAYMENT_RECONCILIATION",
                run.getId(),
                "PAYMENT_RECONCILED",
                user.organizationId(),
                user.userId(),
                Map.of(
                    "paymentsChecked", payments.size(),
                    "matchesCreated", matchesCreated,
                    "unmatchedCount", payments.size() - matchesCreated
                )
            );
        } catch (RuntimeException exception) {
            run.setStatus(ReconciliationStatus.FAILED);
            run.setCompletedAt(OffsetDateTime.now());
            run.setErrorMessage(exception.getMessage());
            runRepository.save(run);
            throw exception;
        }

        return toResponse(run);
    }

    @Transactional(readOnly = true)
    public ReconciliationResponse getRun(
        UUID runId,
        CurrentPaymentUser user
    ) {
        PaymentReconciliationRun run = runRepository
            .findByIdAndOrganizationId(runId, user.organizationId())
            .orElseThrow(() -> PaymentException.notFound(
                "Запуск сверки %s не найден".formatted(runId)
            ));
        return toResponse(run);
    }

    private PaymentReconciliationRun startRun(CurrentPaymentUser user) {
        PaymentReconciliationRun run = new PaymentReconciliationRun();
        run.setOrganizationId(user.organizationId());
        run.setStatus(ReconciliationStatus.PROCESSING);
        run.setPaymentsChecked(0);
        run.setMatchesCreated(0);
        run.setUnmatchedCount(0);
        run.setParameters(Map.of("mode", "MVP_SYNCHRONOUS"));
        run.setStartedBy(user.userId());
        run.setStartedAt(OffsetDateTime.now());
        return runRepository.save(run);
    }

    private void completeRun(
        PaymentReconciliationRun run,
        int checked,
        int matched
    ) {
        run.setPaymentsChecked(checked);
        run.setMatchesCreated(matched);
        run.setUnmatchedCount(checked - matched);
        run.setStatus(ReconciliationStatus.COMPLETED);
        run.setCompletedAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    private Optional<PaymentTargetCandidate> findCandidate(
        Payment payment,
        UUID organizationId
    ) {
        List<ClaimPaymentData> claimsByPurpose =
            claimRepository.findMentionedInPurpose(
                organizationId,
                payment.getPurpose()
            );
        if (claimsByPurpose.size() == 1) {
            ClaimPaymentData claim = claimsByPurpose.getFirst();
            return candidateForClaim(claim);
        }

        List<PaymentTargetCandidate> shipmentsByPurpose =
            targetRepository.findShipmentsMentionedInPurpose(
                organizationId,
                payment.getPurpose()
            );
        if (shipmentsByPurpose.size() == 1) {
            return Optional.of(shipmentsByPurpose.getFirst());
        }

        List<PaymentTargetCandidate> exactClaims = claimRepository
            .findOpenByPayerInn(organizationId, payment.getPayerInn())
            .stream()
            .map(this::candidateForClaim)
            .flatMap(Optional::stream)
            .filter(candidate -> candidate.remainingAmount()
                .compareTo(payment.getAmount().abs()) == 0)
            .toList();
        if (exactClaims.size() == 1) {
            return Optional.of(exactClaims.getFirst());
        }

        List<PaymentTargetCandidate> exactShipments = targetRepository
            .findShipmentsByPayerInn(organizationId, payment.getPayerInn())
            .stream()
            .filter(candidate -> candidate.remainingAmount()
                .compareTo(payment.getAmount().abs()) == 0)
            .toList();
        return exactShipments.size() == 1
            ? Optional.of(exactShipments.getFirst())
            : Optional.empty();
    }

    private Optional<PaymentTargetCandidate> candidateForClaim(
        ClaimPaymentData claim
    ) {
        BigDecimal remaining = claim.serviceAmount()
            .subtract(paymentService.paidAmount(claim))
            .max(BigDecimal.ZERO);
        if (remaining.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(new PaymentTargetCandidate(
            PaymentTargetType.CLAIM,
            claim.id(),
            claim.claimNumber(),
            claim.serviceAmount(),
            remaining
        ));
    }

    private boolean createAutomaticMatch(
        Payment payment,
        PaymentTargetCandidate candidate,
        UUID userId
    ) {
        if (payment.getAmount().signum() <= 0) {
            return false;
        }
        BigDecimal amount = paymentService.availableAmount(payment)
            .min(candidate.remainingAmount());
        if (amount.signum() <= 0) {
            return false;
        }

        PaymentMatch match = new PaymentMatch();
        match.setPaymentId(payment.getId());
        match.setTargetType(candidate.targetType());
        match.setTargetId(candidate.targetId());
        match.setMatchedAmount(amount);
        match.setMatchType(PaymentMatchType.AUTOMATIC);
        match.setConfidence(BigDecimal.ONE);
        match.setActive(true);
        match.setMatchedBy(userId);
        match.setMatchedAt(OffsetDateTime.now());
        matchRepository.save(match);
        paymentService.refreshStatus(payment);
        return true;
    }

    private ReconciliationResponse toResponse(
        PaymentReconciliationRun run
    ) {
        return new ReconciliationResponse(
            run.getId(),
            run.getPaymentsChecked(),
            run.getMatchesCreated(),
            run.getUnmatchedCount()
        );
    }
}
