package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.dto.ClaimDetailsResponse;
import ru.sber.cargotech.claim.dto.ClaimListItemResponse;
import ru.sber.cargotech.claim.dto.ClaimVersionResponse;
import ru.sber.cargotech.claim.dto.CreateClaimRequest;
import ru.sber.cargotech.claim.dto.CreateClaimVersionRequest;
import ru.sber.cargotech.claim.dto.StatusChangeRequest;
import ru.sber.cargotech.claim.dto.StatusHistoryResponse;
import ru.sber.cargotech.claim.dto.UpdateClaimRequest;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.entity.ClaimStatusHistory;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimQueryRepository;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimStatusHistoryRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClaimService {
    private static final Collection<ClaimStatus> CLOSED_STATUSES = List.of(
        ClaimStatus.PAID,
        ClaimStatus.CANCELLED,
        ClaimStatus.CLOSED_IN_COURT
    );

    private final ClaimRepository claimRepository;
    private final ClaimQueryRepository queryRepository;
    private final ClaimStatusHistoryRepository historyRepository;
    private final ShipmentService shipmentService;
    private final PaymentClient paymentClient;
    private final ContractService contractService;
    private final PartyService partyService;
    private final ClaimCalculationService calculationService;
    private final ClaimVersionService versionService;
    private final ClaimOutboxWriter outboxWriter;

    @Value("${claim.number-prefix:CLM}")
    private String numberPrefix;

    @Transactional(readOnly = true)
    public Page<ClaimListItemResponse> list(
        CurrentClaimUser user,
        ClaimStatus status,
        UUID creditorId,
        UUID debtorId,
        UUID assignedLawyerId,
        String search,
        Pageable pageable
    ) {
        return queryRepository.findClaims(
            user.organizationId(),
            status,
            creditorId,
            debtorId,
            assignedLawyerId,
            search,
            pageable
        );
    }

    @Transactional(readOnly = true)
    public ClaimDetailsResponse get(CurrentClaimUser user, UUID claimId) {
        return queryRepository.findDetails(user.organizationId(), claimId)
            .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));
    }

    @Transactional
    public ClaimDetailsResponse create(CurrentClaimUser user, CreateClaimRequest request) {
        ClaimShipment shipment = shipmentService.getEntity(user.organizationId(), request.shipmentId());
        ClaimContract contract = contractService.getEntity(user.organizationId(), shipment.getContractId());

        if (claimRepository.existsByShipmentIdAndStatusNotIn(shipment.getId(), CLOSED_STATUSES)) {
            throw ClaimException.conflict("По рейсу уже существует активная претензия");
        }

        UUID creditorId = request.creditorId() == null ? shipment.getExpeditorId() : request.creditorId();
        UUID debtorId = request.debtorId() == null ? shipment.getClientId() : request.debtorId();
        if (creditorId.equals(debtorId)) {
            throw ClaimException.validation("Кредитор и должник должны быть разными контрагентами");
        }
        partyService.getEntity(user.organizationId(), creditorId);
        partyService.getEntity(user.organizationId(), debtorId);

        String claimNumber = request.claimNumber() == null || request.claimNumber().isBlank()
            ? generateNumber(user.organizationId())
            : request.claimNumber().trim();
        if (claimRepository.existsByOrganizationIdAndClaimNumber(user.organizationId(), claimNumber)) {
            throw ClaimException.conflict("Претензия с таким номером уже существует");
        }

        ClaimEntity claim = new ClaimEntity();
        claim.setOrganizationId(user.organizationId());
        claim.setClaimNumber(claimNumber);
        claim.setShipmentId(shipment.getId());
        claim.setContractId(contract.getId());
        claim.setCreditorId(creditorId);
        claim.setDebtorId(debtorId);
        claim.setClaimType(request.claimType() == null ? ClaimType.PAYMENT_DELAY : request.claimType());
        claim.setStatus(ClaimStatus.DRAFT);
        claim.setReason(request.reason());
        claim.setPrincipalDebt(request.principalDebt() == null ? BigDecimal.ZERO : request.principalDebt());
        claim.setPenaltyAmount(request.penaltyAmount() == null ? BigDecimal.ZERO : request.penaltyAmount());
        claim.setAssignedLawyerId(request.assignedLawyerId());
        claim.setCreatedBy(user.userId());
        claim.setUpdatedBy(user.userId());
        applyNonPaymentConfirmation(claim, user, request.nonPaymentConfirmed(), request.nonPaymentConfirmationComment());
        claim.normalizeTotals();
        ClaimEntity saved = claimRepository.save(claim);

        recordStatus(saved.getId(), null, ClaimStatus.DRAFT, "Создание претензии", user.userId());
        outboxWriter.write("CLAIM", saved.getId(), "CLAIM_CREATED", user.organizationId(), user.userId(), Map.of("claimId", saved.getId()));

        calculationService.recalculate(user, saved.getId());

        if (request.draftContent() != null && !request.draftContent().isBlank()) {
            ClaimVersionResponse version = versionService.create(
                user,
                saved.getId(),
                new CreateClaimVersionRequest(null, null, request.draftContent(), "Первичный текст", true)
            );
            saved.setFinalVersionId(version.id());
            claimRepository.save(saved);
        }

        return get(user, saved.getId());
    }

    @Transactional
    public ClaimDetailsResponse update(CurrentClaimUser user, UUID claimId, UpdateClaimRequest request) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        ensureEditable(claim);
        if (request.reason() != null) {
            claim.setReason(request.reason());
        }
        if (request.principalDebt() != null) {
            claim.setPrincipalDebt(request.principalDebt());
        }
        if (request.penaltyAmount() != null) {
            claim.setPenaltyAmount(request.penaltyAmount());
        }
        if (request.assignedLawyerId() != null) {
            claim.setAssignedLawyerId(request.assignedLawyerId());
        }
        if (request.nonPaymentConfirmed() != null) {
            applyNonPaymentConfirmation(
                claim,
                user,
                request.nonPaymentConfirmed(),
                request.nonPaymentConfirmationComment()
            );
        }
        claim.setUpdatedBy(user.userId());
        claim.normalizeTotals();
        ClaimEntity saved = claimRepository.save(claim);
        outboxWriter.write("CLAIM", saved.getId(), "CLAIM_UPDATED", user.organizationId(), user.userId(), Map.of("claimId", saved.getId()));
        return get(user, saved.getId());
    }

    @Transactional
    public void deleteDraft(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw ClaimException.conflict("Удалить можно только черновик претензии");
        }
        claimRepository.delete(claim);
        outboxWriter.write("CLAIM", claim.getId(), "CLAIM_DELETED", user.organizationId(), user.userId(), Map.of("claimId", claim.getId()));
    }

    @Transactional
    public ClaimDetailsResponse submitToLegalReview(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        return changeStatus(user, claimId, ClaimStatus.PENDING_LEGAL_REVIEW, request == null ? null : request.reason());
    }

    @Transactional
    public ClaimDetailsResponse approve(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getFinalVersionId() == null) {
            throw ClaimException.conflict("Нельзя утвердить претензию без финальной версии текста");
        }
        claim.setApprovedAt(OffsetDateTime.now());
        claim.setApprovedBy(user.userId());
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        return changeStatus(user, claimId, ClaimStatus.LEGAL_APPROVED, request == null ? null : request.reason());
    }

    @Transactional
    public ClaimDetailsResponse send(
            CurrentClaimUser user,
            UUID claimId,
            StatusChangeRequest request
    ) {
        ClaimEntity claim = getEntity(
                user.organizationId(),
                claimId
        );

        if (claim.getStatus() != ClaimStatus.LEGAL_APPROVED) {
            throw ClaimException.conflict(
                    "Отправить можно только утверждённую претензию"
            );
        }

        claim.setSentAt(OffsetDateTime.now());
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);

        return changeStatus(
                user,
                claimId,
                ClaimStatus.SENT,
                request == null ? null : request.reason()
        );
    }

    @Transactional
    public ClaimDetailsResponse cancel(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() == ClaimStatus.PAID || claim.getStatus() == ClaimStatus.CLOSED_IN_COURT) {
            throw ClaimException.conflict("Оплаченную или закрытую в суде претензию нельзя отменить");
        }
        claim.setCancelledAt(OffsetDateTime.now());
        claim.setCancellationReasonCode(request == null ? null : request.cancellationReasonCode());
        claim.setCancellationReason(request == null ? null : request.reason());
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        return changeStatus(user, claimId, ClaimStatus.CANCELLED, request == null ? null : request.reason());
    }

    @Transactional
    public ClaimDetailsResponse markPaid(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() == ClaimStatus.CANCELLED || claim.getStatus() == ClaimStatus.CLOSED_IN_COURT) {
            throw ClaimException.conflict("Нельзя отметить оплату по отменённой или закрытой в суде претензии");
        }
        claim.setPaidAt(OffsetDateTime.now());
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        return changeStatus(user, claimId, ClaimStatus.PAID, request == null ? null : request.reason());
    }

    @Transactional
    public ClaimDetailsResponse escalateToCourt(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() != ClaimStatus.SENT && claim.getStatus() != ClaimStatus.AWAITING_RESPONSE) {
            throw ClaimException.conflict("В суд можно эскалировать только отправленную претензию или претензию в ожидании ответа");
        }
        claim.setEscalatedAt(OffsetDateTime.now());
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        return changeStatus(user, claimId, ClaimStatus.ESCALATED_TO_COURT, request == null ? null : request.reason());
    }

    @Transactional
    public ClaimDetailsResponse closeInCourt(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() != ClaimStatus.ESCALATED_TO_COURT) {
            throw ClaimException.conflict("Закрыть в суде можно только эскалированную претензию");
        }
        return changeStatus(user, claimId, ClaimStatus.CLOSED_IN_COURT, request == null ? null : request.reason());
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryResponse> statusHistory(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        return historyRepository.findByClaimIdOrderByChangedAtAsc(claim.getId())
            .stream()
            .map(this::toStatusHistory)
            .toList();
    }

    public ClaimEntity getEntity(UUID organizationId, UUID claimId) {
        return claimRepository.findByIdAndOrganizationId(claimId, organizationId)
            .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));
    }

    private ClaimDetailsResponse changeStatus(
        CurrentClaimUser user,
        UUID claimId,
        ClaimStatus newStatus,
        String reason
    ) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        validateTransition(claim.getStatus(), newStatus);
        ClaimStatus previous = claim.getStatus();
        claim.setStatus(newStatus);
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        recordStatus(claimId, previous, newStatus, reason, user.userId());
        outboxWriter.write(
            "CLAIM",
            claimId,
            "CLAIM_STATUS_CHANGED",
            user.organizationId(),
            user.userId(),
            Map.of(
                "claimId", claimId,
                "previousStatus", previous.name(),
                "newStatus", newStatus.name()
            )
        );
        return get(user, claimId);
    }

    private void validateTransition(ClaimStatus current, ClaimStatus next) {
        if (current == next) {
            throw ClaimException.conflict("Претензия уже находится в статусе " + next);
        }
        boolean valid = switch (next) {
            case PENDING_LEGAL_REVIEW -> current == ClaimStatus.DRAFT;
            case LEGAL_APPROVED -> current == ClaimStatus.DRAFT || current == ClaimStatus.PENDING_LEGAL_REVIEW;
            case SENT -> current == ClaimStatus.LEGAL_APPROVED;
            case PAID -> current != ClaimStatus.CANCELLED && current != ClaimStatus.CLOSED_IN_COURT;
            case CANCELLED -> current != ClaimStatus.PAID && current != ClaimStatus.CLOSED_IN_COURT;
            case ESCALATED_TO_COURT -> current == ClaimStatus.SENT || current == ClaimStatus.AWAITING_RESPONSE;
            case CLOSED_IN_COURT -> current == ClaimStatus.ESCALATED_TO_COURT;
            case DRAFT, AWAITING_RESPONSE -> false;
        };
        if (!valid) {
            throw ClaimException.conflict("Недопустимый переход статуса: " + current + " -> " + next);
        }
    }

    private void ensureEditable(ClaimEntity claim) {
        if (claim.getStatus() == ClaimStatus.SENT
            || claim.getStatus() == ClaimStatus.AWAITING_RESPONSE
            || claim.getStatus() == ClaimStatus.PAID
            || claim.getStatus() == ClaimStatus.ESCALATED_TO_COURT
            || claim.getStatus() == ClaimStatus.CANCELLED
            || claim.getStatus() == ClaimStatus.CLOSED_IN_COURT) {
            throw ClaimException.conflict("Редактировать можно только черновик, претензию на проверке или утверждённую претензию");
        }
    }

    private void applyNonPaymentConfirmation(
        ClaimEntity claim,
        CurrentClaimUser user,
        Boolean confirmed,
        String comment
    ) {
        if (confirmed == null) {
            return;
        }
        claim.setNonPaymentConfirmed(confirmed);
        claim.setNonPaymentConfirmationComment(comment);
        if (confirmed) {
            claim.setNonPaymentConfirmedAt(OffsetDateTime.now());
            claim.setNonPaymentConfirmedBy(user.userId());
        } else {
            claim.setNonPaymentConfirmedAt(null);
            claim.setNonPaymentConfirmedBy(null);
        }
    }

    private void recordStatus(
        UUID claimId,
        ClaimStatus previousStatus,
        ClaimStatus newStatus,
        String reason,
        UUID userId
    ) {
        ClaimStatusHistory history = new ClaimStatusHistory();
        history.setClaimId(claimId);
        history.setPreviousStatus(previousStatus);
        history.setNewStatus(newStatus);
        history.setReason(reason);
        history.setChangedBy(userId);
        historyRepository.save(history);
    }

    private StatusHistoryResponse toStatusHistory(ClaimStatusHistory history) {
        return new StatusHistoryResponse(
            history.getId(),
            history.getClaimId(),
            history.getPreviousStatus(),
            history.getNewStatus(),
            history.getReason(),
            history.getChangedBy(),
            history.getChangedAt()
        );
    }

    private String generateNumber(UUID organizationId) {
        String date = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String number = numberPrefix + "-" + date + "-" + suffix;
        if (claimRepository.existsByOrganizationIdAndClaimNumber(organizationId, number)) {
            return generateNumber(organizationId);
        }
        return number;
    }
}
