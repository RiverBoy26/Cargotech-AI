package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import ru.sber.cargotech.claim.dto.ContractRequest;
import ru.sber.cargotech.claim.dto.ContractIntakeRequest;
import ru.sber.cargotech.claim.dto.ContractResponse;
import ru.sber.cargotech.claim.dto.ContractExtractionResponse;
import ru.sber.cargotech.claim.dto.SubmitContractExtractionRequest;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimContractClause;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ContractExtractedValue;
import ru.sber.cargotech.claim.enums.ClauseType;
import ru.sber.cargotech.claim.enums.ContractExtractionField;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PaymentScheduleType;
import ru.sber.cargotech.claim.enums.PenaltyCapBase;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.enums.TermDayType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimContractClauseRepository;
import ru.sber.cargotech.claim.repository.ClaimContractRepository;
import ru.sber.cargotech.claim.repository.ContractExtractedValueRepository;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractService {
    private final ClaimContractRepository contractRepository;
    private final ContractExtractedValueRepository extractedValueRepository;
    private final ClaimContractClauseRepository contractClauseRepository;
    private final PartyService partyService;
    private final ClaimOutboxWriter outboxWriter;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<ContractResponse> list(CurrentClaimUser user, Pageable pageable) {
        log.debug("Получение договоров: organizationId={}, page={}, size={}", user.organizationId(), pageable.getPageNumber(), pageable.getPageSize());

        return contractRepository
            .findByOrganizationIdAndDeletedAtIsNull(user.organizationId(), pageable)
            .map(contract -> toResponse(user.organizationId(), contract));
    }

    @Transactional(readOnly = true)
    public ContractResponse get(CurrentClaimUser user, UUID id) {
        log.debug("Получение договора: contractId={}, organizationId={}", id, user.organizationId());

        return toResponse(user.organizationId(), getEntity(user.organizationId(), id));
    }

    @Transactional
    public ContractResponse create(CurrentClaimUser user, ContractIntakeRequest request) {
        UUID expeditorId = user.organizationId();
        log.debug(
            "Загрузка договора на распознавание: organizationId={}, userId={}, clientId={}, documentId={}",
            user.organizationId(), user.userId(), request.clientId(), request.documentId()
        );

        validateParties(user.organizationId(), request.clientId(), expeditorId);
        ClaimContract contract = new ClaimContract();
        contract.setOrganizationId(user.organizationId());
        contract.setClientId(request.clientId());
        contract.setExpeditorId(expeditorId);
        contract.setDocumentId(request.documentId());
        contract.setStatus(ContractStatus.DRAFT);
        contract.setCreatedBy(user.userId());
        contract.setUpdatedBy(user.userId());
        ClaimContract saved = contractRepository.save(contract);
        outboxWriter.write("CONTRACT", saved.getId(), "CONTRACT_CREATED", user.organizationId(), user.userId(), Map.of("contractId", saved.getId()));
        requestExtraction(saved, user);
        return toResponse(user.organizationId(), saved);
    }

    @Transactional
    public ContractResponse update(CurrentClaimUser user, UUID id, ContractRequest request) {
        log.debug("Обновление договора: contractId={}, organizationId={}, userId={}, number={}, paymentDays={}, penaltyType={}, penaltyRate={}", id, user.organizationId(), user.userId(), request.number(), request.paymentDays(), request.penaltyType(), request.penaltyRate());

        ClaimContract contract = getEntity(user.organizationId(), id);
        UUID previousDocumentId = contract.getDocumentId();
        UUID expeditorId = user.organizationId();
        validateParties(user.organizationId(), request.clientId(), expeditorId);
        String requestedNumber = request.number().trim();
        if (contractRepository.existsByOrganizationIdAndNumberAndDeletedAtIsNullAndIdNot(
            user.organizationId(), requestedNumber, contract.getId()
        )) {
            throw ClaimException.conflict("Договор с таким номером уже существует");
        }
        apply(contract, request, user.userId(), expeditorId);
        ClaimContract saved = contractRepository.save(contract);
        outboxWriter.write("CONTRACT", saved.getId(), "CONTRACT_UPDATED", user.organizationId(), user.userId(), Map.of("contractId", saved.getId()));
        if (!Objects.equals(previousDocumentId, saved.getDocumentId())) {
            if (saved.getDocumentId() == null) {
                clearExtraction(saved);
            } else {
                requestExtraction(saved, user);
            }
        }
        return toResponse(user.organizationId(), saved);
    }

    @Transactional
    public void delete(CurrentClaimUser user, UUID id) {
        log.debug("Удаление договора: contractId={}, organizationId={}, userId={}", id, user.organizationId(), user.userId());

        ClaimContract contract = getEntity(user.organizationId(), id);
        contract.setDeletedAt(OffsetDateTime.now());
        contract.setUpdatedBy(user.userId());
        contractRepository.save(contract);
        outboxWriter.write("CONTRACT", contract.getId(), "CONTRACT_DELETED", user.organizationId(), user.userId(), Map.of("contractId", contract.getId()));
    }

    public ClaimContract getEntity(UUID organizationId, UUID id) {
        return contractRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, organizationId)
            .orElseThrow(() -> ClaimException.notFound("Договор не найден"));
    }

    @Transactional(readOnly = true)
    public ContractExtractionResponse getExtraction(CurrentClaimUser user, UUID contractId) {
        ClaimContract contract = getEntity(user.organizationId(), contractId);
        return toExtractionResponse(contract);
    }

    @Transactional
    public ContractExtractionResponse submitExtraction(
        CurrentClaimUser user,
        UUID contractId,
        SubmitContractExtractionRequest request
    ) {
        ClaimContract contract = getEntity(user.organizationId(), contractId);
        if (contract.getDocumentId() == null) {
            throw ClaimException.conflict("Сначала прикрепите файл договора");
        }
        saveExtractionResults(contract, user.organizationId(), user.userId(), request);
        return toExtractionResponse(contract);
    }

    @Transactional
    public void completeAutomaticExtraction(
        UUID organizationId,
        UUID contractId,
        UUID requestedBy,
        SubmitContractExtractionRequest request
    ) {
        ClaimContract contract = getEntity(organizationId, contractId);
        if (contract.getExtractionStatus() != ContractExtractionStatus.PENDING) return;
        saveExtractionResults(contract, organizationId, requestedBy, request);
    }

    @Transactional
    public void markExtractionFailed(UUID organizationId, UUID contractId, UUID requestedBy) {
        ClaimContract contract = getEntity(organizationId, contractId);
        if (contract.getExtractionStatus() != ContractExtractionStatus.PENDING) return;
        contract.setExtractionStatus(ContractExtractionStatus.FAILED);
        contract.setUpdatedBy(requestedBy);
        contractRepository.save(contract);
        outboxWriter.write(
            "CONTRACT", contractId, "CONTRACT_EXTRACTION_FAILED",
            organizationId, requestedBy, Map.of("contractId", contractId)
        );
    }

    @Transactional
    public ContractExtractionResponse confirmExtraction(CurrentClaimUser user, UUID contractId) {
        ClaimContract contract = getEntity(user.organizationId(), contractId);
        if (contract.getExtractionStatus() != ContractExtractionStatus.REVIEW_REQUIRED) {
            throw ClaimException.conflict("Нет результатов разбора договора, ожидающих подтверждения");
        }
        List<ContractExtractedValue> candidates = extractedValueRepository.findByContractIdOrderByCreatedAtAsc(contractId);
        applyConfirmedCandidates(contract, candidates, user.userId());
        if (contractRepository.existsByOrganizationIdAndNumberAndDeletedAtIsNullAndIdNot(
            user.organizationId(), contract.getNumber(), contract.getId()
        )) {
            throw ClaimException.conflict("Договор с таким номером уже существует");
        }
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setExtractionStatus(ContractExtractionStatus.CONFIRMED);
        contract.setExtractionConfirmedAt(OffsetDateTime.now());
        contract.setExtractionConfirmedBy(user.userId());
        contract.setUpdatedBy(user.userId());
        try {
            contractRepository.saveAndFlush(contract);
        } catch (DataIntegrityViolationException exception) {
            throw ClaimException.conflict("Договор с таким номером уже существует");
        }
        outboxWriter.write(
            "CONTRACT", contractId, "CONTRACT_EXTRACTION_CONFIRMED",
            user.organizationId(), user.userId(), Map.of("contractId", contractId)
        );
        return toExtractionResponse(contract);
    }

    private void apply(
        ClaimContract contract,
        ContractRequest request,
        UUID userId,
        UUID expeditorId
    ) {
        contract.setNumber(request.number().trim());
        contract.setClientId(request.clientId());
        contract.setExpeditorId(expeditorId);
        contract.setSignedAt(request.signedAt());
        contract.setValidFrom(request.validFrom());
        contract.setValidTo(request.validTo());
        contract.setStatus(request.status() == null ? ContractStatus.ACTIVE : request.status());
        contract.setPaymentDays(request.paymentDays());
        contract.setPaymentDayType(request.paymentDayType());
        contract.setPaymentStartEvent(request.paymentStartEvent());
        contract.setPaymentScheduleType(request.paymentScheduleType());
        contract.setPaymentWeekDays(normalizePaymentWeekDays(request.paymentWeekDays()));
        validatePaymentSchedule(contract);
        contract.setPenaltyType(request.penaltyType() == null ? PenaltyType.ARTICLE_395 : request.penaltyType());
        contract.setPenaltyRate(request.penaltyRate());
        contract.setPenaltyCapPercent(request.penaltyCapPercent());
        contract.setPenaltyCapBase(request.penaltyCapBase());
        validatePenaltyCap(contract);
        contract.setClaimResponseDays(request.claimResponseDays() == null ? 30 : request.claimResponseDays());
        contract.setClaimResponseDayType(request.claimResponseDayType() == null ? TermDayType.CALENDAR_DAYS : request.claimResponseDayType());
        contract.setJurisdiction(request.jurisdiction());
        contract.setDocumentId(request.documentId());
        contract.setUpdatedBy(userId);
    }

    private void validateParties(UUID organizationId, UUID clientId, UUID expeditorId) {
        if (clientId.equals(expeditorId)) {
            throw ClaimException.validation("Клиент и экспедитор должны быть разными контрагентами");
        }
        partyService.getEntity(organizationId, clientId);
        partyService.getEntity(organizationId, expeditorId);
    }

    public ContractResponse toResponse(UUID organizationId, ClaimContract contract) {
        ClaimParty client = partyService.getEntity(organizationId, contract.getClientId());
        ClaimParty expeditor = partyService.getEntity(organizationId, contract.getExpeditorId());
        return new ContractResponse(
            contract.getId(),
            contract.getOrganizationId(),
            contract.getNumber(),
            contract.getClientId(),
            client.getName(),
            contract.getExpeditorId(),
            expeditor.getName(),
            contract.getSignedAt(),
            contract.getValidFrom(),
            contract.getValidTo(),
            contract.getStatus(),
            contract.getPaymentDays(),
            contract.getPaymentDayType(),
            contract.getPaymentStartEvent(),
            contract.getPaymentScheduleType(),
            contract.getPaymentWeekDays(),
            contract.getPenaltyType(),
            contract.getPenaltyRate(),
            contract.getPenaltyCapPercent(),
            contract.getPenaltyCapBase(),
            contract.getClaimResponseDays(),
            contract.getClaimResponseDayType(),
            contract.getJurisdiction(),
            contract.getDocumentId(),
            contract.getExtractionStatus(),
            contract.getExtractionConfirmedAt(),
            contract.getExtractionConfirmedBy(),
            contract.getCreatedAt(),
            contract.getUpdatedAt()
        );
    }

    private void requestExtraction(ClaimContract contract, CurrentClaimUser user) {
        extractedValueRepository.deleteByContractId(contract.getId());
        contractClauseRepository.deleteByContractIdAndExtractedTrue(contract.getId());
        contract.setExtractionStatus(ContractExtractionStatus.PENDING);
        contract.setExtractionConfirmedAt(null);
        contract.setExtractionConfirmedBy(null);
        contractRepository.save(contract);
        outboxWriter.write(
            "CONTRACT", contract.getId(), "CONTRACT_EXTRACTION_REQUESTED",
            user.organizationId(), user.userId(),
            Map.of("contractId", contract.getId(), "documentId", contract.getDocumentId())
        );
        eventPublisher.publishEvent(new ContractExtractionRequestedEvent(
            contract.getId(), user.organizationId(), user.userId(), contract.getDocumentId()
        ));
    }

    private void saveExtractionResults(
        ClaimContract contract,
        UUID organizationId,
        UUID userId,
        SubmitContractExtractionRequest request
    ) {
        validateCandidates(request);
        extractedValueRepository.deleteByContractId(contract.getId());
        List<ContractExtractedValue> values = request.candidates().stream().map(candidate -> {
            ContractExtractedValue value = new ContractExtractedValue();
            value.setContractId(contract.getId());
            value.setField(candidate.field());
            value.setValue(blankToNull(candidate.value()));
            value.setSource(blankToNull(candidate.source()));
            value.setSourcePage(candidate.sourcePage());
            value.setConfidence(candidate.confidence());
            value.setClauseNumber(blankToNull(candidate.clauseNumber()));
            value.setClauseType(candidate.clauseType());
            value.setManuallyEdited(candidate.manuallyEdited());
            value.setCreatedBy(userId);
            return value;
        }).toList();
        extractedValueRepository.saveAll(values);
        contract.setExtractionStatus(ContractExtractionStatus.REVIEW_REQUIRED);
        contract.setExtractionConfirmedAt(null);
        contract.setExtractionConfirmedBy(null);
        contract.setUpdatedBy(userId);
        contractRepository.save(contract);
        outboxWriter.write(
            "CONTRACT", contract.getId(), "CONTRACT_EXTRACTION_REVIEW_REQUIRED",
            organizationId, userId,
            Map.of("contractId", contract.getId(), "candidateCount", values.size())
        );
    }

    private void clearExtraction(ClaimContract contract) {
        extractedValueRepository.deleteByContractId(contract.getId());
        contractClauseRepository.deleteByContractIdAndExtractedTrue(contract.getId());
        contract.setExtractionStatus(ContractExtractionStatus.NOT_STARTED);
        contract.setExtractionConfirmedAt(null);
        contract.setExtractionConfirmedBy(null);
        contractRepository.save(contract);
    }

    private void validateCandidates(SubmitContractExtractionRequest request) {
        Set<ContractExtractionField> scalarFields = EnumSet.noneOf(ContractExtractionField.class);
        for (var candidate : request.candidates()) {
            if (candidate.field() == ContractExtractionField.EXACT_CLAUSE) {
                if (blankToNull(candidate.value()) == null || candidate.clauseType() == null) {
                    throw ClaimException.validation("Для пункта договора нужны точный текст и категория");
                }
                continue;
            }
            if (!scalarFields.add(candidate.field())) {
                throw ClaimException.validation("Поле разбора договора не должно повторяться: " + candidate.field());
            }
        }
        Set<ContractExtractionField> requiredReviewFields = EnumSet.allOf(ContractExtractionField.class);
        requiredReviewFields.remove(ContractExtractionField.EXACT_CLAUSE);
        if (!scalarFields.equals(requiredReviewFields)) {
            throw ClaimException.validation("Экран проверки должен содержать все поля договора");
        }
    }

    private void applyConfirmedCandidates(
        ClaimContract contract,
        List<ContractExtractedValue> candidates,
        UUID userId
    ) {
        // The uploaded document becomes the only source of extracted values.
        // Missing fields are deliberately null instead of being guessed.
        contract.setNumber(null);
        contract.setSignedAt(null);
        contract.setPaymentDays(null);
        contract.setPaymentDayType(null);
        contract.setPaymentStartEvent(null);
        contract.setPaymentScheduleType(null);
        contract.setPaymentWeekDays(null);
        contract.setPenaltyType(null);
        contract.setPenaltyRate(null);
        contract.setPenaltyCapPercent(null);
        contract.setPenaltyCapBase(null);
        contract.setClaimResponseDays(null);
        contract.setClaimResponseDayType(null);
        contract.setJurisdiction(null);
        contractClauseRepository.deleteByContractIdAndExtractedTrue(contract.getId());

        for (ContractExtractedValue candidate : candidates) {
            String value = blankToNull(candidate.getValue());
            if (candidate.getField() == ContractExtractionField.EXACT_CLAUSE) {
                if (value != null) saveExtractedClause(contract.getId(), candidate, userId);
                continue;
            }
            if (value == null) continue;
            try {
                switch (candidate.getField()) {
                    case CONTRACT_NUMBER -> contract.setNumber(contractNumber(value));
                    case SIGNED_AT -> contract.setSignedAt(contractSignedAt(value));
                    case PAYMENT_DAYS -> contract.setPaymentDays(nonNegativeInteger(value));
                    case PAYMENT_DAY_TYPE -> contract.setPaymentDayType(TermDayType.valueOf(value));
                    case PAYMENT_START_EVENT -> contract.setPaymentStartEvent(PaymentStartEvent.valueOf(value));
                    case PAYMENT_SCHEDULE_TYPE -> contract.setPaymentScheduleType(PaymentScheduleType.valueOf(value));
                    case PAYMENT_WEEK_DAYS -> contract.setPaymentWeekDays(normalizePaymentWeekDays(value));
                    case PENALTY_TYPE -> contract.setPenaltyType(PenaltyType.valueOf(value));
                    case PENALTY_RATE -> contract.setPenaltyRate(nonNegativeDecimal(value));
                    case PENALTY_CAP_PERCENT -> contract.setPenaltyCapPercent(nonNegativeDecimal(value));
                    case PENALTY_CAP_BASE -> contract.setPenaltyCapBase(PenaltyCapBase.valueOf(value));
                    case CLAIM_RESPONSE_DAYS -> contract.setClaimResponseDays(nonNegativeInteger(value));
                    case CLAIM_RESPONSE_DAY_TYPE -> contract.setClaimResponseDayType(TermDayType.valueOf(value));
                    case JURISDICTION -> contract.setJurisdiction(value);
                    case EXACT_CLAUSE -> { }
                }
            } catch (IllegalArgumentException exception) {
                throw ClaimException.validation("Неверный формат извлечённого поля " + candidate.getField() + ": " + value);
            }
        }
        validatePaymentSchedule(contract);
        validatePenaltyCap(contract);
        if (contract.getPenaltyType() == null) contract.setPenaltyType(PenaltyType.ARTICLE_395);
        if (contract.getClaimResponseDays() == null) contract.setClaimResponseDays(30);
        if (contract.getClaimResponseDayType() == null) contract.setClaimResponseDayType(TermDayType.CALENDAR_DAYS);
        if (contract.getNumber() == null) {
            throw ClaimException.validation("Укажите номер договора перед подтверждением");
        }
    }

    private void saveExtractedClause(UUID contractId, ContractExtractedValue candidate, UUID userId) {
        ClaimContractClause clause = new ClaimContractClause();
        clause.setContractId(contractId);
        clause.setClauseNumber(candidate.getClauseNumber());
        clause.setClauseType(candidate.getClauseType() == null ? ClauseType.OTHER : candidate.getClauseType());
        clause.setText(candidate.getValue().trim());
        clause.setSourcePage(candidate.getSourcePage());
        clause.setExtracted(true);
        clause.setCreatedBy(userId);
        clause.setUpdatedBy(userId);
        contractClauseRepository.save(clause);
    }

    private void validatePenaltyCap(ClaimContract contract) {
        if (contract.getPenaltyCapPercent() == null && contract.getPenaltyCapBase() == null) return;
        if (contract.getPenaltyType() != PenaltyType.CONTRACT_PENALTY) {
            throw ClaimException.validation("Ограничение размера применяется только к договорной неустойке");
        }
        if (contract.getPenaltyCapPercent() == null || contract.getPenaltyCapBase() == null) {
            throw ClaimException.validation("Для ограничения неустойки укажите и процент, и базу расчёта");
        }
        if (contract.getPenaltyCapPercent().signum() < 0) {
            throw ClaimException.validation("Лимит неустойки не может быть отрицательным");
        }
    }

    private void validatePaymentSchedule(ClaimContract contract) {
        String weekDays = normalizePaymentWeekDays(contract.getPaymentWeekDays());
        contract.setPaymentWeekDays(weekDays);
        if (contract.getPaymentScheduleType() == null) {
            if (weekDays != null) {
                throw ClaimException.validation("Платёжные дни нельзя указать без порядка применения платёжного календаря");
            }
            return;
        }
        if (contract.getPaymentScheduleType() == PaymentScheduleType.NEXT_PAYMENT_DAY && weekDays == null) {
            throw ClaimException.validation("Для переноса на ближайший платёжный день укажите дни недели");
        }
    }

    private String normalizePaymentWeekDays(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;

        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String token : normalized.split(",")) {
            String item = token.trim();
            if (item.isEmpty()) continue;
            try {
                days.add(DayOfWeek.valueOf(item.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw ClaimException.validation("Неизвестный платёжный день недели: " + item);
            }
        }
        if (days.isEmpty()) return null;
        return days.stream()
            .sorted()
            .map(DayOfWeek::name)
            .collect(java.util.stream.Collectors.joining(","));
    }

    private int nonNegativeInteger(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException();
        return parsed;
    }

    private BigDecimal nonNegativeDecimal(String value) {
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.signum() < 0) throw new IllegalArgumentException();
        return parsed;
    }

    private String contractNumber(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 128) throw new IllegalArgumentException();
        return normalized;
    }

    private LocalDate contractSignedAt(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private ContractExtractionResponse toExtractionResponse(ClaimContract contract) {
        var candidates = extractedValueRepository.findByContractIdOrderByCreatedAtAsc(contract.getId()).stream()
            .map(value -> new ContractExtractionResponse.Candidate(
                value.getField(), value.getValue(), value.getSource(), value.getSourcePage(),
                value.getConfidence(), value.getClauseNumber(), value.getClauseType(),
                value.isManuallyEdited()
            ))
            .toList();
        return new ContractExtractionResponse(
            contract.getId(), contract.getDocumentId(), contract.getExtractionStatus(), candidates,
            contract.getExtractionConfirmedAt(), contract.getExtractionConfirmedBy()
        );
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
