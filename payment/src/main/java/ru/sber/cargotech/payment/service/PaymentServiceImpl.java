package ru.sber.cargotech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import ru.sber.cargotech.payment.client.ClaimClient;
import ru.sber.cargotech.payment.dto.ClaimPaymentContextResponse;
import ru.sber.cargotech.payment.dto.ClaimPaymentsResponse;
import ru.sber.cargotech.payment.dto.CreatePaymentRequest;
import ru.sber.cargotech.payment.dto.PaymentDetailsResponse;
import ru.sber.cargotech.payment.dto.PaymentMatchResponse;
import ru.sber.cargotech.payment.dto.PaymentResponse;
import ru.sber.cargotech.payment.entity.OrganizationRecipient;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentMatch;
import ru.sber.cargotech.payment.enums.PaymentCheckStatus;
import ru.sber.cargotech.payment.enums.PaymentSourceSystem;
import ru.sber.cargotech.payment.enums.PaymentStatus;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.exception.PaymentException;
import ru.sber.cargotech.payment.repository.ClaimPaymentData;
import ru.sber.cargotech.payment.repository.OrganizationRecipientRepository;
import ru.sber.cargotech.payment.repository.PaymentMatchRepository;
import ru.sber.cargotech.payment.repository.PaymentOutboxWriter;
import ru.sber.cargotech.payment.repository.PaymentRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMatchRepository matchRepository;
    private final PaymentOutboxWriter outboxWriter;
    private final ClaimClient claimClient;
    private final OrganizationRecipientRepository organizationRecipientRepository;
    private final ClaimPaymentSynchronizationService claimSynchronizationService;

    @Transactional
    public PaymentResponse create(
            CreatePaymentRequest request,
            CurrentPaymentUser user
    ) {
        log.debug(
                "Ручное создание платежа: organizationId={}, userId={}, paymentNumber={}, paymentDate={}, amount={}",
                user.organizationId(),
                user.userId(),
                request.paymentNumber(),
                request.paymentDate(),
                request.amount()
        );

        if (request.amount().signum() == 0) {
            throw PaymentException.unprocessable(
                    "Сумма платежа не может быть равна нулю"
            );
        }

        String externalPaymentId = normalizeOptional(request.externalPaymentId());
        if (externalPaymentId != null
                && paymentRepository
                .existsByOrganizationIdAndSourceSystemAndExternalPaymentId(
                        user.organizationId(),
                        PaymentSourceSystem.MANUAL_EXCEL,
                        externalPaymentId
                )) {
            throw PaymentException.conflict(
                    "Платёж с таким внешним идентификатором уже существует"
            );
        }

        OrganizationRecipient recipient = requireOrganizationRecipient(
                user.organizationId()
        );

        Payment payment = new Payment();
        payment.setOrganizationId(user.organizationId());
        payment.setSourceSystem(PaymentSourceSystem.MANUAL_EXCEL);
        payment.setExternalPaymentId(externalPaymentId);
        payment.setPaymentNumber(normalizeOptional(request.paymentNumber()));
        payment.setPaymentDate(request.paymentDate());
        payment.setPayerInn(normalizeOptional(request.payerInn()));
        payment.setPayerName(normalizeOptional(request.payerName()));
        payment.setRecipientInn(normalizeOptional(recipient.getInn()));
        payment.setRecipientName(recipient.getName());
        payment.setAmount(request.amount());
        payment.setCurrency(normalizeCurrency(request.currency()));
        payment.setPurpose(normalizeOptional(request.purpose()));
        payment.setStatus(PaymentStatus.IMPORTED);
        payment.setRawData(Map.of("entryMode", "MANUAL"));
        payment.setCreatedAt(java.time.OffsetDateTime.now());

        Payment saved = paymentRepository.save(payment);
        outboxWriter.write(
                "PAYMENT",
                saved.getId(),
                "PAYMENT_CREATED",
                user.organizationId(),
                user.userId(),
                Map.of(
                        "paymentId", saved.getId(),
                        "sourceSystem", saved.getSourceSystem().name(),
                        "amount", saved.getAmount(),
                        "currency", saved.getCurrency()
                )
        );
        return toResponse(saved);
    }

    public Page<PaymentResponse> findAll(
            Pageable pageable,
            CurrentPaymentUser user
    ) {
        log.debug("Получение списка платежей: organizationId={}, userId={}, page={}, size={}", user.organizationId(), user.userId(), pageable.getPageNumber(), pageable.getPageSize());

        return paymentRepository
                .findAllByOrganizationId(user.organizationId(), pageable)
                .map(this::toResponse);
    }

    public PaymentDetailsResponse getDetails(
            UUID paymentId,
            CurrentPaymentUser user
    ) {
        log.debug("Получение платежа: paymentId={}, organizationId={}, userId={}", paymentId, user.organizationId(), user.userId());

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
        log.debug("Получение состояния оплаты претензии: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        ClaimPaymentData claim = getClaim(claimId, user.organizationId());

        List<UUID> paymentIds = paymentIds(claim);

        List<PaymentResponse> payments = paymentIds.stream()
                .map(id -> getPayment(id, user.organizationId()))
                .map(this::toResponse)
                .toList();

        BigDecimal paid = paidAmount(claim);
        BigDecimal remaining = ClaimOutstandingAmountCalculator.calculate(
                claim,
                paid
        );
        BigDecimal expected = paid.add(remaining);

        return new ClaimPaymentsResponse(
                claim.id(),
                claim.shipmentId(),
                claim.claimNumber(),
                expected,
                paid,
                remaining,
                resolvePaymentStatus(expected, paid),
                lastPaymentDate(claim, user.organizationId()),
                payments
        );
    }

    public Payment getPayment(
            UUID paymentId,
            UUID organizationId
    ) {
        log.debug("Поиск платежа: paymentId={}, organizationId={}", paymentId, organizationId);

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
        log.debug("Запрос контекста претензии из claim: claimId={}, organizationId={}", claimId, organizationId);

        try {
            ClaimPaymentContextResponse response =
                    claimClient.getPaymentContext(claimId);

            if (response == null) {
                throw PaymentException.notFound(
                        "Претензия %s не найдена".formatted(claimId)
                );
            }

            return toClaimPaymentData(response);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw PaymentException.notFound(
                        "Претензия %s не найдена".formatted(claimId)
                );
            }

            throw PaymentException.conflict(
                    "Не удалось получить данные претензии из claim: "
                            + exception.getMessage()
            );
        }
    }

    public List<ClaimPaymentData> findClaimsMentionedInPurpose(
            UUID organizationId,
            String purpose
    ) {
        log.debug("Поиск претензий по назначению: organizationId={}, purposeLength={}", organizationId, purpose == null ? 0 : purpose.length());

        if (purpose == null || purpose.isBlank()) {
            return List.of();
        }

        return claimClient.findMentionedInPurpose(purpose)
                .stream()
                .map(this::toClaimPaymentData)
                .toList();
    }

    public List<ClaimPaymentData> findOpenClaimsByPayerInn(
            UUID organizationId,
            String payerInn
    ) {
        log.debug("Поиск открытых претензий по ИНН: organizationId={}, payerInn={}", organizationId, payerInn);

        if (payerInn == null || payerInn.isBlank()) {
            return List.of();
        }

        return claimClient.findOpenByPayerInn(payerInn)
                .stream()
                .map(this::toClaimPaymentData)
                .toList();
    }

    public void updateLastPaymentCheck(
            UUID claimId,
            UUID organizationId,
            UUID checkId,
            BigDecimal remainingPrincipalAmount,
            BigDecimal remainingPenaltyAmount
    ) {
        log.debug("Передача проверки оплаты в claim: claimId={}, checkId={}, organizationId={}", claimId, checkId, organizationId);

        try {
            claimClient.updateLastPaymentCheck(
                    claimId,
                    checkId,
                    remainingPrincipalAmount,
                    remainingPenaltyAmount
            );
        } catch (RestClientResponseException exception) {
            throw PaymentException.conflict(
                    "Не удалось привязать проверку оплаты к претензии: "
                            + exception.getMessage()
            );
        }
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
        log.debug("Пересчёт статуса платежа: paymentId={}, currentStatus={}, amount={}", payment.getId(), payment.getStatus(), payment.getAmount());

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
        OrganizationRecipient recipient = organizationRecipientRepository
                .findById(payment.getOrganizationId())
                .orElse(null);
        String recipientInn = recipient == null
                ? payment.getRecipientInn()
                : recipient.getInn();
        String recipientName = recipient == null
                ? payment.getRecipientName()
                : recipient.getName();

        return new PaymentResponse(
                payment.getId(),
                payment.getSourceSystem(),
                payment.getExternalPaymentId(),
                payment.getPaymentNumber(),
                payment.getPaymentDate(),
                payment.getPayerInn(),
                payment.getPayerName(),
                recipientInn,
                recipientName,
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPurpose(),
                payment.getStatus(),
                matched,
                payment.getAmount().abs()
                        .subtract(matched)
                        .max(BigDecimal.ZERO)
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

    private ClaimPaymentData toClaimPaymentData(
            ClaimPaymentContextResponse response
    ) {
        return new ClaimPaymentData(
                response.claimId(),
                response.shipmentId(),
                response.claimNumber(),
                response.debtorInn(),
                response.shipmentOrderNumber(),
                response.serviceAmount(),
                response.calculatedPaidAmount(),
                response.remainingPrincipalAmount(),
                response.remainingPenaltyAmount(),
                response.status()
        );
    }

    @Transactional
    public void delete(
            UUID paymentId,
            String reason,
            CurrentPaymentUser user
    ) {
        log.debug(
                "Удаление платежа: paymentId={}, organizationId={}, userId={}",
                paymentId,
                user.organizationId(),
                user.userId()
        );

        String normalizedReason = normalizeOptional(reason);
        if (normalizedReason == null) {
            throw PaymentException.unprocessable(
                "Укажите причину удаления платежа"
            );
        }
        if (normalizedReason.length() > 2000) {
            throw PaymentException.unprocessable(
                "Причина удаления платежа не должна превышать 2000 символов"
            );
        }

        Payment payment = getPayment(paymentId, user.organizationId());
        List<UUID> affectedClaimIds = matchRepository
                .findActiveTargetIdsByPaymentIdAndType(
                        paymentId,
                        PaymentTargetType.CLAIM
                );

        paymentRepository.delete(payment);
        outboxWriter.write(
                "PAYMENT",
                paymentId,
                "PAYMENT_DELETED",
                user.organizationId(),
                user.userId(),
                Map.of(
                    "paymentId", paymentId,
                    "reason", normalizedReason
                )
        );

        affectedClaimIds.forEach(
            claimId -> claimSynchronizationService.synchronizeAfterCommit(
                claimId,
                "Удалён сопоставленный платёж %s: %s"
                    .formatted(paymentId, normalizedReason)
            )
        );
    }

    private String normalizeCurrency(String currency) {
        String normalized = normalizeOptional(currency);
        return normalized == null ? "RUB" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OrganizationRecipient requireOrganizationRecipient(
            UUID organizationId
    ) {
        return organizationRecipientRepository.findById(organizationId)
                .orElseThrow(() -> PaymentException.notFound(
                        "Организация-экспедитор %s не найдена"
                                .formatted(organizationId)
                ));
    }
}
