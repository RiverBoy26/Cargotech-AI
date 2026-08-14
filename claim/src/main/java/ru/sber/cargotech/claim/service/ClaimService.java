package ru.sber.cargotech.claim.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.client.PaymentClient;
import ru.sber.cargotech.claim.client.DocumentTextClient;
import ru.sber.cargotech.claim.dto.ClaimDetailsResponse;
import ru.sber.cargotech.claim.dto.AccountantClaimSubmissionRequest;
import ru.sber.cargotech.claim.dto.ClaimListItemResponse;
import ru.sber.cargotech.claim.dto.ClaimVersionResponse;
import ru.sber.cargotech.claim.dto.CreateClaimRequest;
import ru.sber.cargotech.claim.dto.CreateClaimVersionRequest;
import ru.sber.cargotech.claim.dto.StatusChangeRequest;
import ru.sber.cargotech.claim.dto.StatusHistoryResponse;
import ru.sber.cargotech.claim.dto.PaymentPreflightResponse;
import ru.sber.cargotech.claim.dto.SendChecklistResponse;
import ru.sber.cargotech.claim.dto.UpdateClaimRequest;
import ru.sber.cargotech.claim.dto.UpdateAccountantDraftRequest;
import ru.sber.cargotech.claim.dto.ValidationOverrideRequest;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.entity.ClaimStatusHistory;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimType;
import ru.sber.cargotech.claim.enums.ClaimVersionSource;
import ru.sber.cargotech.claim.enums.DocumentValidationStatus;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaimService {
    private static final UUID SYSTEM_ACTOR_ID = new UUID(0L, 0L);
    private static final Collection<ClaimStatus> CLOSED_STATUSES = List.of(
        ClaimStatus.PAID,
        ClaimStatus.CANCELLED,
        ClaimStatus.CANCELLED_PAID,
        ClaimStatus.CLOSED_IN_COURT
    );
    private static final Collection<ClaimStatus> DELETABLE_STATUSES = List.of(
        ClaimStatus.DRAFT,
        ClaimStatus.PAID,
        ClaimStatus.CANCELLED
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
    private DocumentTextClient documentTextClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    void setDocumentTextClient(DocumentTextClient documentTextClient) {
        this.documentTextClient = documentTextClient;
    }

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
        log.debug("Поиск претензий: organizationId={}, userId={}, status={}, creditorId={}, debtorId={}, assignedLawyerId={}, searchPresent={}, page={}, size={}", user.organizationId(), user.userId(), status, creditorId, debtorId, assignedLawyerId, search != null && !search.isBlank(), pageable.getPageNumber(), pageable.getPageSize());

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
        log.debug("Получение претензии: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        return queryRepository.findDetails(user.organizationId(), claimId)
            .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));
    }

    @Transactional
    public ClaimDetailsResponse create(CurrentClaimUser user, CreateClaimRequest request) {
        return createInternal(user, request, true);
    }

    @Transactional
    public ClaimDetailsResponse createDraftForShipment(CurrentClaimUser user, CreateClaimRequest request) {
        return createInternal(user, request, false);
    }

    private ClaimDetailsResponse createInternal(
        CurrentClaimUser user,
        CreateClaimRequest request,
        boolean calculateImmediately
    ) {
        log.debug("Создание претензии: organizationId={}, userId={}, shipmentId={}, claimType={}, assignedLawyerId={}, nonPaymentConfirmed={}", user.organizationId(), user.userId(), request.shipmentId(), request.claimType(), request.assignedLawyerId(), request.nonPaymentConfirmed());

        ClaimShipment shipment = shipmentService.getEntity(user.organizationId(), request.shipmentId());
        ClaimContract contract = contractService.getEntity(user.organizationId(), shipment.getContractId());

        if (claimRepository.existsByOrganizationIdAndShipmentIdAndStatusNotIn(
                user.organizationId(),
                shipment.getId(),
                CLOSED_STATUSES
        )) {
            throw ClaimException.conflict("По рейсу уже существует активная претензия");
        }

        UUID creditorId = request.creditorId() == null ? shipment.getExpeditorId() : request.creditorId();
        UUID debtorId = request.debtorId() == null ? shipment.getClientId() : request.debtorId();
        if (creditorId.equals(debtorId)) {
            throw ClaimException.validation("Кредитор и должник должны быть разными контрагентами");
        }
        partyService.getEntity(user.organizationId(), creditorId);
        var debtor = partyService.getEntity(user.organizationId(), debtorId);

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
        claim.setRecipientName(debtor.getName());
        claim.setRecipientEmail(debtor.getEmail());
        claim.setRecipientAddress(
            debtor.getPostalAddress() == null || debtor.getPostalAddress().isBlank()
                ? debtor.getLegalAddress()
                : debtor.getPostalAddress()
        );
        claim.setResponseDeadlineDays(contract.getClaimResponseDays());
        claim.setSignerFullName(user.fullName());
        claim.setSignerPosition(user.hasRole("LAWYER") ? "Юрист" : null);
        // Monetary values are backend-owned. The API keeps the legacy request
        // fields for compatibility, but they must never override shipment/payment data.
        claim.setPrincipalDebt(shipment.getServiceAmount());
        claim.setPenaltyAmount(BigDecimal.ZERO);
        claim.setAssignedLawyerId(request.assignedLawyerId());
        claim.setCreatedBy(user.userId());
        claim.setUpdatedBy(user.userId());
        applyNonPaymentConfirmation(claim, user, request.nonPaymentConfirmed(), request.nonPaymentConfirmationComment());
        claim.normalizeTotals();
        // The response is loaded immediately through ClaimQueryRepository (JDBC).
        // Flush the JPA insert first so the JDBC query in this transaction can see it.
        ClaimEntity saved = claimRepository.saveAndFlush(claim);

        recordStatus(saved.getId(), null, ClaimStatus.DRAFT, "Создание претензии", user.userId());
        outboxWriter.write("CLAIM", saved.getId(), "CLAIM_CREATED", user.organizationId(), user.userId(), Map.of("claimId", saved.getId()));

        if (calculateImmediately) {
            var calculation = calculationService.recalculate(user, saved.getId());
            if (calculation.remainingDebt() == null || calculation.remainingDebt().signum() <= 0) {
                throw ClaimException.conflict(
                    "Претензию нельзя создать: по рейсу отсутствует непогашенная задолженность"
                );
            }
        }

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
        log.debug("Обновление претензии: claimId={}, organizationId={}, userId={}, assignedLawyerId={}, nonPaymentConfirmed={}", claimId, user.organizationId(), user.userId(), request.assignedLawyerId(), request.nonPaymentConfirmed());

        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        ensureEditable(claim);
        if (request.reason() != null) {
            claim.setReason(request.reason());
        }
        if (request.claimNumber() != null && !request.claimNumber().isBlank()) {
            String claimNumber = request.claimNumber().trim();
            if (!claimNumber.equals(claim.getClaimNumber())
                    && claimRepository.existsByOrganizationIdAndClaimNumber(user.organizationId(), claimNumber)) {
                throw ClaimException.conflict("Претензия с таким номером уже существует");
            }
            claim.setClaimNumber(claimNumber);
        }
        if (request.recipientName() != null) {
            claim.setRecipientName(request.recipientName());
        }
        if (request.recipientEmail() != null) {
            claim.setRecipientEmail(request.recipientEmail());
        }
        if (request.recipientAddress() != null) {
            claim.setRecipientAddress(request.recipientAddress());
        }
        if (request.bankDetails() != null) {
            claim.setBankDetails(request.bankDetails());
        }
        if (request.responseDeadlineDays() != null) {
            claim.setResponseDeadlineDays(request.responseDeadlineDays());
        }
        if (request.signerFullName() != null) {
            claim.setSignerFullName(request.signerFullName());
        }
        if (request.signerPosition() != null) {
            claim.setSignerPosition(request.signerPosition());
        }
        if (request.signerAuthority() != null) {
            claim.setSignerAuthority(request.signerAuthority());
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
        if (request.text() != null && !request.text().isBlank()) {
            ClaimVersionResponse version = versionService.create(
                user,
                saved.getId(),
                new CreateClaimVersionRequest(
                    ClaimVersionSource.LAWYER,
                    saved.getFinalVersionId(),
                    request.text(),
                    "Новая редакция юриста",
                    true
                )
            );
            saved.setFinalVersionId(version.id());
            saved.setDocumentValidationStatus(DocumentValidationStatus.PENDING);
            saved.setDocumentValidationErrors(null);
            clearValidationOverride(saved);
            claimRepository.save(saved);
        }
        outboxWriter.write("CLAIM", saved.getId(), "CLAIM_UPDATED", user.organizationId(), user.userId(), Map.of("claimId", saved.getId()));
        return get(user, saved.getId());
    }

    @Transactional
    public ClaimDetailsResponse updateAccountantDraft(
        CurrentClaimUser user,
        UUID claimId,
        UpdateAccountantDraftRequest request
    ) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw ClaimException.conflict("Бухгалтер может редактировать только черновик претензии");
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            claim.setReason(request.reason().trim());
        }
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        versionService.create(
            user,
            claimId,
            new CreateClaimVersionRequest(
                ClaimVersionSource.ACCOUNTANT,
                claim.getFinalVersionId(),
                request.text().trim(),
                "Черновик отредактирован бухгалтером",
                false
            )
        );
        return get(user, claimId);
    }

    @Transactional
    public ClaimDetailsResponse requestNonPaymentConfirmation(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw ClaimException.conflict("Запросить подтверждение можно только для черновика претензии");
        }
        claim.setNonPaymentConfirmed(false);
        claim.setNonPaymentConfirmedAt(null);
        claim.setNonPaymentConfirmedBy(null);
        claim.setNonPaymentConfirmationRequestedAt(OffsetDateTime.now());
        claim.setNonPaymentConfirmationRequestedBy(user.userId());
        claim.setUpdatedBy(user.userId());
        ClaimEntity saved = claimRepository.save(claim);
        outboxWriter.write(
            "CLAIM",
            saved.getId(),
            "CLAIM_NON_PAYMENT_CONFIRMATION_REQUESTED",
            user.organizationId(),
            user.userId(),
            Map.of("claimId", saved.getId())
        );
        return get(user, saved.getId());
    }

    @Transactional
    public void delete(CurrentClaimUser user, UUID claimId) {
        log.debug("Удаление претензии: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (!DELETABLE_STATUSES.contains(claim.getStatus())) {
            throw ClaimException.conflict("Удалить претензию можно только в статусе Черновик, Оплачено или Отменено");
        }
        UUID shipmentId = claim.getShipmentId();
        shipmentService.getEntity(user.organizationId(), shipmentId);
        if (claimRepository.existsByOrganizationIdAndShipmentIdAndIdNot(
            user.organizationId(),
            shipmentId,
            claimId
        )) {
            throw ClaimException.conflict(
                "Нельзя удалить рейс: с ним связана другая претензия"
            );
        }

        paymentClient.deleteForClaimAndShipment(claimId, shipmentId);
        claimRepository.delete(claim);
        claimRepository.flush();
        shipmentService.delete(user, shipmentId);
        outboxWriter.write("CLAIM", claim.getId(), "CLAIM_DELETED", user.organizationId(), user.userId(), Map.of("claimId", claim.getId()));
    }

    @Transactional
    public ClaimDetailsResponse submitToLegalReview(
            CurrentClaimUser user,
            UUID claimId,
            AccountantClaimSubmissionRequest request
    ) {
        log.debug(
                "Подтверждение неуплаты и передача претензии на юридическую проверку: " +
                        "claimId={}, organizationId={}, userId={}",
                claimId,
                user.organizationId(),
                user.userId()
        );

        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw ClaimException.validation("Укажите основание претензии");
        }
        if (request.text() == null || request.text().isBlank()) {
            throw ClaimException.validation("Укажите текст претензии");
        }

        var calculation = calculationService.recalculate(user, claimId);
        if (calculation.remainingDebt() == null || calculation.remainingDebt().signum() <= 0) {
            throw ClaimException.conflict(
                "Подтвердить неуплату нельзя: по рейсу отсутствует непогашенная задолженность"
            );
        }

        // Recalculation runs in REQUIRES_NEW and updates the claim's monetary
        // fields, which increments its optimistic-lock version. Load the claim
        // only after that transaction commits so this persistence context does
        // not retain a stale entity version.
        ClaimEntity claim = getEntity(
                user.organizationId(),
                claimId
        );

        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw ClaimException.conflict(
                    "Передать на юридическую проверку можно только претензию " +
                            "в статусе DRAFT"
            );
        }

        String reason = request.reason().trim();
        claim.setReason(reason);
        ClaimVersionResponse version = versionService.create(
            user,
            claimId,
            new CreateClaimVersionRequest(
                ClaimVersionSource.ACCOUNTANT,
                claim.getFinalVersionId(),
                request.text().trim(),
                "Текст подготовлен бухгалтером перед подтверждением неуплаты",
                true
            )
        );
        claim.setFinalVersionId(version.id());

        applyNonPaymentConfirmation(
                claim,
                user,
                true,
                reason
        );

        claim.setUpdatedBy(user.userId());

        ClaimEntity saved = claimRepository.save(claim);

        outboxWriter.write(
                "CLAIM",
                saved.getId(),
                "CLAIM_NON_PAYMENT_CONFIRMED",
                user.organizationId(),
                user.userId(),
                Map.of(
                        "claimId", saved.getId(),
                        "confirmed", true,
                        "confirmedBy", user.userId()
                )
        );

        ClaimDetailsResponse response = changeStatus(
                user,
                saved.getId(),
                ClaimStatus.PENDING_LEGAL_REVIEW,
                reason
        );

        log.debug(
                "Неуплата подтверждена, претензия передана на юридическую проверку: " +
                        "claimId={}, organizationId={}, userId={}, newStatus={}",
                saved.getId(),
                user.organizationId(),
                user.userId(),
                ClaimStatus.PENDING_LEGAL_REVIEW
        );

        return response;
    }

    @Transactional
    public ClaimDetailsResponse confirmNonPayment(
            CurrentClaimUser user,
            UUID claimId,
            StatusChangeRequest request
    ) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw ClaimException.conflict(
                    "Подтвердить отсутствие оплаты можно только для претензии в статусе DRAFT"
            );
        }

        String reason = request == null ? null : request.reason();
        applyNonPaymentConfirmation(claim, user, true, reason);
        claim.setNonPaymentConfirmationRequestedAt(null);
        claim.setNonPaymentConfirmationRequestedBy(null);
        claim.setUpdatedBy(user.userId());
        ClaimEntity saved = claimRepository.save(claim);

        outboxWriter.write(
                "CLAIM",
                saved.getId(),
                "CLAIM_NON_PAYMENT_CONFIRMED",
                user.organizationId(),
                user.userId(),
                Map.of(
                        "claimId", saved.getId(),
                        "confirmed", true,
                        "confirmedBy", user.userId()
                )
        );

        return get(user, saved.getId());
    }

    @Transactional
    public ClaimDetailsResponse approve(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        log.debug("Утверждение претензии: claimId={}, userId={}", claimId, user.userId());

        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        boolean confirmedDraft = claim.getStatus() == ClaimStatus.DRAFT
            && claim.isNonPaymentConfirmed();
        if (claim.getStatus() != ClaimStatus.PENDING_LEGAL_REVIEW && !confirmedDraft) {
            throw ClaimException.conflict(
                "Утвердить можно претензию на юридической проверке или подтверждённый бухгалтером черновик"
            );
        }
        if (claim.getFinalVersionId() == null) {
            throw ClaimException.conflict("Нельзя утвердить претензию без финальной версии текста");
        }
        ensureValidationAllowsSend(claim);
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
        log.debug("Начало отправки претензии с preflight-проверкой оплаты: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        ClaimEntity claim = getEntity(
                user.organizationId(),
                claimId
        );

        if (claim.getStatus() != ClaimStatus.LEGAL_APPROVED) {
            throw ClaimException.conflict(
                "Отправить можно только утверждённую претензию"
            );
        }
        var calculation = calculationService.recalculate(user, claimId);
        if (calculation.totalAmount() == null || calculation.totalAmount().signum() <= 0) {
            throw ClaimException.conflict("Отправка заблокирована: задолженность погашена");
        }
        SendChecklistResponse checklist = buildSendChecklist(claim);
        if (!checklist.readyToSend()) {
            throw ClaimException.conflict(
                "Отправка заблокирована: " + String.join("; ", checklist.warnings())
            );
        }

        PaymentPreflightResponse preflight = paymentClient.preflightCheck(
            claimId,
            "Проверка оплаты перед отправкой претензии"
        );
        if (preflight == null || !preflight.isCanSend()) {
            throw ClaimException.conflict(
                "Отправка заблокирована: задолженность погашена или результат проверки оплаты недоступен"
            );
        }

        // Payment preflight updates the same claim through the internal API and
        // increments its optimistic-lock version in a separate transaction.
        // Reload it before changing the delivery status to avoid saving a stale entity.
        entityManager.refresh(claim);
        claim.setSentAt(OffsetDateTime.now());
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);

        changeStatus(
                user,
                claimId,
                ClaimStatus.SENT,
                request == null ? null : request.reason()
        );
        return changeStatus(
                user,
                claimId,
                ClaimStatus.AWAITING_RESPONSE,
                "SYSTEM: претензия отправлена, ожидается ответ должника",
                SYSTEM_ACTOR_ID
        );
    }

    @Transactional(readOnly = true)
    public SendChecklistResponse sendChecklist(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        return buildSendChecklist(claim);
    }

    private SendChecklistResponse buildSendChecklist(ClaimEntity claim) {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        BigDecimal outstandingAmount = safeAmount(claim.getPrincipalDebt())
            .add(safeAmount(claim.getPenaltyAmount()));
        checks.put("debt", outstandingAmount.signum() > 0);
        checks.put("penaltyCalculation", claim.getPenaltyAmount() != null);
        checks.put("partyDetails", claim.getCreditorId() != null && claim.getDebtorId() != null);
        checks.put("contractReferences", claim.getContractId() != null);
        checks.put("legalBasis", !claim.isManualReviewRequired()
            || (claim.getManualReviewReason() != null && !claim.getManualReviewReason().isBlank()));
        boolean storedDocumentsReady = storedDocumentsReady(claim);
        checks.put("attachments", storedDocumentsReady);
        checks.put("paymentConfirmed", claim.isNonPaymentConfirmed());
        checks.put("finalVersion", claim.getFinalVersionId() != null);
        checks.put("validation", claim.getDocumentValidationStatus() == DocumentValidationStatus.PASSED
            || claim.getDocumentValidationStatus() == DocumentValidationStatus.OVERRIDDEN);

        List<String> warnings = new ArrayList<>();
        checks.forEach((name, passed) -> {
            if (!passed) {
                warnings.add(checklistWarning(name));
            }
        });
        return new SendChecklistResponse(
            claim.getId(),
            warnings.isEmpty(),
            claim.getDocumentValidationStatus(),
            claim.isManualReviewRequired(),
            checks,
            List.copyOf(warnings)
        );
    }

    private boolean storedDocumentsReady(ClaimEntity claim) {
        // Unit-level consumers may instantiate ClaimService directly. Production always
        // receives the internal document client and checks persisted links, not a UI flag.
        if (documentTextClient == null) {
            return claim.getFinalVersionId() != null;
        }
        try {
            DocumentTextClient.ClaimDocumentReadinessResponse readiness =
                documentTextClient.getClaimReadiness(claim.getId());
            return readiness.generatedClaimPresent()
                && readiness.calculationPdfPresent()
                && readiness.calculationXlsxPresent();
        } catch (RuntimeException exception) {
            log.warn("Проверка сохранённых документов недоступна: claimId={}", claim.getId());
            return false;
        }
    }

    @Transactional
    public ClaimDetailsResponse overrideValidation(
        CurrentClaimUser user,
        UUID claimId,
        ValidationOverrideRequest request
    ) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        ensureEditable(claim);
        claim.setDocumentValidationStatus(DocumentValidationStatus.OVERRIDDEN);
        claim.setValidationOverriddenAt(OffsetDateTime.now());
        claim.setValidationOverriddenBy(user.userId());
        claim.setValidationOverrideReason(request.reason().trim());
        claim.setUpdatedBy(user.userId());
        ClaimEntity saved = claimRepository.save(claim);
        outboxWriter.write(
            "CLAIM",
            saved.getId(),
            "CLAIM_VALIDATION_OVERRIDDEN",
            user.organizationId(),
            user.userId(),
            Map.of("claimId", saved.getId(), "reason", request.reason().trim())
        );
        return get(user, saved.getId());
    }

    @Transactional
    public ClaimDetailsResponse awaitResponse(
        CurrentClaimUser user,
        UUID claimId,
        StatusChangeRequest request
    ) {
        return changeStatus(
            user,
            claimId,
            ClaimStatus.AWAITING_RESPONSE,
            request == null ? null : request.reason()
        );
    }

    @Transactional
    public ClaimDetailsResponse cancel(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        log.debug("Отмена претензии: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() == ClaimStatus.PAID
                || claim.getStatus() == ClaimStatus.CANCELLED_PAID
                || claim.getStatus() == ClaimStatus.CLOSED_IN_COURT) {
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
    public ClaimDetailsResponse withdraw(
        CurrentClaimUser user,
        UUID claimId,
        StatusChangeRequest request
    ) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getSentAt() != null
            || claim.getStatus() == ClaimStatus.SENT
            || claim.getStatus() == ClaimStatus.AWAITING_RESPONSE
            || claim.getStatus() == ClaimStatus.ESCALATED_TO_COURT
            || claim.getStatus() == ClaimStatus.CLOSED_IN_COURT) {
            throw ClaimException.conflict(
                "Бухгалтер может отозвать претензию только до отправки должнику"
            );
        }
        if (CLOSED_STATUSES.contains(claim.getStatus())) {
            throw ClaimException.conflict("Претензия уже закрыта");
        }

        String reason = request == null || request.reason() == null
            || request.reason().isBlank()
            ? "Претензия отозвана бухгалтером до отправки"
            : request.reason().trim();
        claim.setCancelledAt(OffsetDateTime.now());
        claim.setCancellationReasonCode("ACCOUNTANT_WITHDRAWAL");
        claim.setCancellationReason(reason);
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        return changeStatus(
            user,
            claimId,
            ClaimStatus.CANCELLED,
            reason
        );
    }

    @Transactional
    public ClaimDetailsResponse markPaid(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        log.debug("Перевод претензии в PAID: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        var calculation = calculationService.recalculate(user, claimId);
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() == ClaimStatus.CANCELLED
                || claim.getStatus() == ClaimStatus.CANCELLED_PAID
                || claim.getStatus() == ClaimStatus.CLOSED_IN_COURT) {
            throw ClaimException.conflict("Нельзя отметить оплату по отменённой или закрытой в суде претензии");
        }
        if (calculation.totalAmount() == null || calculation.totalAmount().signum() > 0) {
            throw ClaimException.conflict("Нельзя отметить претензию оплаченной: задолженность погашена не полностью");
        }
        return closeAsPaid(
            user,
            claim,
            request == null ? "Полная оплата подтверждена" : request.reason(),
            false
        );
    }

    @Transactional
    public void synchronizePaymentState(CurrentClaimUser user, UUID claimId) {
        synchronizePaymentState(user, claimId, null);
    }

    @Transactional
    public void synchronizePaymentState(
            CurrentClaimUser user,
            UUID claimId,
            String reopenReason
    ) {
        log.debug("Синхронизация расчёта после фиксации платежа: claimId={}, organizationId={}, userId={}", claimId, user.organizationId(), user.userId());

        var calculation = calculationService.recalculate(user, claimId);
        // recalculate() runs in REQUIRES_NEW and increments the claim version.
        // Load the claim only after that transaction finishes; otherwise this
        // transaction keeps a stale managed entity and PAID fails with an
        // optimistic-lock conflict after the debt has already become zero.
        ClaimEntity claim = getEntity(user.organizationId(), claimId);

        if (calculation.totalAmount() == null) {
            return;
        }

        if (calculation.totalAmount().signum() > 0) {
            if (claim.getStatus() == ClaimStatus.PAID
                    && claim.getSentAt() != null) {
                claim.setPaidAt(null);
                claim.setUpdatedBy(SYSTEM_ACTOR_ID);
                claimRepository.save(claim);
                changeStatus(
                    user,
                    claimId,
                    ClaimStatus.AWAITING_RESPONSE,
                    reopenReason == null || reopenReason.isBlank()
                        ? "Оплата отменена, задолженность восстановлена"
                        : reopenReason,
                    SYSTEM_ACTOR_ID
                );
            }
            return;
        }

        if (CLOSED_STATUSES.contains(claim.getStatus())) {
            return;
        }

        closeAsPaid(
            user,
            claim,
            "Задолженность полностью погашена",
            true
        );
    }

    private ClaimDetailsResponse closeAsPaid(
        CurrentClaimUser user,
        ClaimEntity claim,
        String reason,
        boolean systemActor
    ) {
        ClaimStatus targetStatus = claim.getSentAt() == null
            ? ClaimStatus.CANCELLED_PAID
            : ClaimStatus.PAID;
        OffsetDateTime now = OffsetDateTime.now();
        claim.setPaidAt(now);
        if (targetStatus == ClaimStatus.CANCELLED_PAID) {
            claim.setCancelledAt(now);
            claim.setCancellationReasonCode("FULL_PAYMENT_BEFORE_SEND");
            claim.setCancellationReason("Задолженность полностью погашена до отправки претензии");
        }
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        return changeStatus(
            user,
            claim.getId(),
            targetStatus,
            reason,
            systemActor ? SYSTEM_ACTOR_ID : user.userId()
        );
    }

    @Transactional
    public ClaimDetailsResponse escalateToCourt(CurrentClaimUser user, UUID claimId, StatusChangeRequest request) {
        log.debug("Эскалация претензии в суд: claimId={}, userId={}", claimId, user.userId());

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
        log.debug("Закрытие судебной работы: claimId={}, userId={}", claimId, user.userId());

        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        if (claim.getStatus() != ClaimStatus.ESCALATED_TO_COURT) {
            throw ClaimException.conflict("Закрыть в суде можно только эскалированную претензию");
        }
        return changeStatus(user, claimId, ClaimStatus.CLOSED_IN_COURT, request == null ? null : request.reason());
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryResponse> statusHistory(CurrentClaimUser user, UUID claimId) {
        log.debug("Получение истории статусов: claimId={}, organizationId={}", claimId, user.organizationId());

        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        return historyRepository.findByClaimIdOrderByChangedAtAsc(claim.getId())
            .stream()
            .map(history -> toStatusHistory(history, claim))
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
        return changeStatus(user, claimId, newStatus, reason, user.userId());
    }

    private ClaimDetailsResponse changeStatus(
        CurrentClaimUser user,
        UUID claimId,
        ClaimStatus newStatus,
        String reason,
        UUID actorId
    ) {
        ClaimEntity claim = getEntity(user.organizationId(), claimId);
        validateTransition(claim.getStatus(), newStatus);
        ClaimStatus previous = claim.getStatus();
        log.debug("Изменение статуса претензии: claimId={}, currentStatus={}, targetStatus={}, userId={}", claim.getId(), claim.getStatus(), newStatus, user.userId());

        claim.setStatus(newStatus);
        claim.setUpdatedBy(actorId);
        claimRepository.save(claim);
        recordStatus(claimId, previous, newStatus, reason, actorId);
        outboxWriter.write(
            "CLAIM",
            claimId,
            "CLAIM_STATUS_CHANGED",
            user.organizationId(),
            actorId,
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
            case LEGAL_APPROVED -> current == ClaimStatus.PENDING_LEGAL_REVIEW || current == ClaimStatus.DRAFT;
            case SENT -> current == ClaimStatus.LEGAL_APPROVED;
            case PAID -> current != ClaimStatus.CANCELLED && current != ClaimStatus.CLOSED_IN_COURT;
            case CANCELLED -> current != ClaimStatus.PAID && current != ClaimStatus.CLOSED_IN_COURT;
            case CANCELLED_PAID -> current == ClaimStatus.DRAFT
                    || current == ClaimStatus.PENDING_LEGAL_REVIEW
                    || current == ClaimStatus.LEGAL_APPROVED;
            case ESCALATED_TO_COURT -> current == ClaimStatus.SENT || current == ClaimStatus.AWAITING_RESPONSE;
            case CLOSED_IN_COURT -> current == ClaimStatus.ESCALATED_TO_COURT;
            case AWAITING_RESPONSE -> current == ClaimStatus.SENT
                    || current == ClaimStatus.PAID;
            case DRAFT -> false;
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
            || claim.getStatus() == ClaimStatus.CANCELLED_PAID
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

    private StatusHistoryResponse toStatusHistory(ClaimStatusHistory history, ClaimEntity claim) {
        boolean systemActor = SYSTEM_ACTOR_ID.equals(history.getChangedBy());
        return new StatusHistoryResponse(
            history.getId(),
            history.getClaimId(),
            history.getPreviousStatus(),
            history.getNewStatus(),
            systemActor ? stripSystemPrefix(history.getReason()) : history.getReason(),
            history.getChangedBy(),
            systemActor ? systemActorLabel(claim) : userFullName(history.getChangedBy()),
            history.getChangedAt()
        );
    }

    private static BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private String systemActorLabel(ClaimEntity claim) {
        UUID lawyerId = claim.getAssignedLawyerId() != null
            ? claim.getAssignedLawyerId()
            : claim.getCreatedBy();
        String lawyerName = userFullName(lawyerId);
        String clientName = partyService.getEntity(claim.getOrganizationId(), claim.getDebtorId()).getName();
        return lawyerName + " - " + clientName;
    }

    private String userFullName(UUID userId) {
        if (userId == null || SYSTEM_ACTOR_ID.equals(userId)) return "Неизвестный пользователь";
        try {
            return jdbcTemplate.queryForObject(
                """
                select concat_ws(' ', last_name, first_name, middle_name)
                from cargotech.auth_users
                where id = ?
                """,
                String.class,
                userId
            );
        } catch (EmptyResultDataAccessException exception) {
            return "Пользователь " + userId;
        }
    }

    private String stripSystemPrefix(String reason) {
        if (reason == null) return null;
        return reason.replaceFirst("(?i)^SYSTEM:\\s*", "");
    }

    private void ensureValidationAllowsSend(ClaimEntity claim) {
        if (claim.getDocumentValidationStatus() != DocumentValidationStatus.PASSED
            && claim.getDocumentValidationStatus() != DocumentValidationStatus.OVERRIDDEN) {
            throw ClaimException.conflict(
                "Документ не прошёл проверку: завершите проверку, исправьте ошибки или подтвердите её вручную"
            );
        }
    }

    private void clearValidationOverride(ClaimEntity claim) {
        claim.setValidationOverriddenAt(null);
        claim.setValidationOverriddenBy(null);
        claim.setValidationOverrideReason(null);
    }

    private String checklistWarning(String check) {
        return switch (check) {
            case "debt" -> "не подтверждена непогашенная задолженность";
            case "penaltyCalculation" -> "не выполнен расчёт пени";
            case "partyDetails" -> "не заполнены стороны претензии";
            case "contractReferences" -> "не указан договор";
            case "legalBasis" -> "требуется ручная проверка правового основания";
            case "attachments" -> "нет финального документа для приложения";
            case "paymentConfirmed" -> "бухгалтер не подтвердил отсутствие оплаты";
            case "finalVersion" -> "не выбрана финальная версия";
            case "validation" -> "проверка документа завершилась ошибкой";
            default -> "не выполнена проверка " + check;
        };
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
