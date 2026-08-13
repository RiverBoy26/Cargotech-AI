package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.client.AiClient;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimResponse;
import ru.sber.cargotech.claim.dto.ClaimVersionResponse;
import ru.sber.cargotech.claim.dto.CreateClaimVersionRequest;
import ru.sber.cargotech.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.claim.entity.*;
import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimVersionSource;
import ru.sber.cargotech.claim.enums.ClauseType;
import ru.sber.cargotech.claim.enums.DocumentValidationStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.mapper.ClaimAiRequestMapper;
import ru.sber.cargotech.claim.repository.*;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaimGenerationService {
    private final ClaimRepository claimRepository;
    private final ClaimPartyRepository partyRepository;
    private final ClaimContractRepository contractRepository;
    private final ClaimShipmentRepository shipmentRepository;
    private final ClaimCalculationRepository calculationRepository;
    private final ClaimAiRequestMapper requestMapper;
    private final AiClient aiClient;
    private final ClaimVersionService versionService;
    private final ClaimCalculationService calculationService;
    private ClaimContractClauseRepository contractClauseRepository;

    @Autowired(required = false)
    void setContractClauseRepository(ClaimContractClauseRepository contractClauseRepository) {
        this.contractClauseRepository = contractClauseRepository;
    }

    // Deliberately NOT transactional as a whole:
    // recalculation must commit before the external AI call, and no database
    // transaction/row lock should be held during a 20-60 second provider request.
    public GenerateClaimResponse generate(CurrentClaimUser user, UUID claimId) {
        // A document must always be based on today's payment and overdue state.
        calculationService.recalculate(user, claimId);
        GenerationContext context = loadContext(user, claimId);
        validateForGeneration(context);
        validateSignatory(user);

        log.info("Запуск AI-генерации: claimId={}, organizationId={}, userId={}",
                claimId, user.organizationId(), user.userId());

        List<ClaimContractClause> contractClauses = contractClauseRepository == null
                ? List.of()
                : contractClauseRepository.findAllByContractIdAndActiveTrueOrderByClauseNumberAsc(
                        context.contract().getId()
                );
        boolean paymentClauseMissing = contractClauses.stream()
                .noneMatch(clause -> clause.getClauseType() == ClauseType.PAYMENT_TERMS
                        && !isBlank(clause.getText()));

        var aiRequest = requestMapper.map(
                context.claim(),
                context.creditor(),
                context.debtor(),
                context.contract(),
                context.shipment(),
                context.calculation(),
                user,
                contractClauses
        );
        AiGenerateClaimResponse aiResponse = aiClient.generate(aiRequest, user.userId());

        validateAiResponse(aiResponse);
        AiGenerateClaimResponse.GeneratedClaim generated = aiResponse.generatedClaim();
        List<String> warnings = new ArrayList<>(collectWarnings(aiResponse));
        if (paymentClauseMissing) {
            warnings.add("Не найден пункт договора, определяющий срок оплаты. Требуется ручная проверка");
        }
        boolean guardrailBlocked = aiResponse.guardrailResult() != null
            && "BLOCK".equalsIgnoreCase(aiResponse.guardrailResult().decision());
        boolean guardrailReview = aiResponse.guardrailResult() != null
            && "REVIEW".equalsIgnoreCase(aiResponse.guardrailResult().decision());
        boolean validationFailed = guardrailBlocked
            || (aiResponse.guardrailResult() != null
                && aiResponse.guardrailResult().errors() != null
                && !aiResponse.guardrailResult().errors().isEmpty());
        boolean manualReviewRequired = Boolean.TRUE.equals(generated.manualReviewRequired())
            || guardrailReview
            || validationFailed
            || paymentClauseMissing;
        DocumentValidationStatus validationStatus = validationFailed
            ? DocumentValidationStatus.FAILED
            : DocumentValidationStatus.PASSED;
        String validationErrors = warnings.isEmpty() ? null : String.join("\n", warnings);
        String usedSources = buildUsedSources(aiRequest, aiResponse);

        ClaimVersionResponse version = versionService.create(
                user,
                claimId,
                new CreateClaimVersionRequest(
                        ClaimVersionSource.AI,
                        null,
                        generated.claimText(),
                        manualReviewRequired
                                ? "Черновик сформирован AI. Требуется ручная проверка."
                                : "Черновик автоматически сформирован AI.",
                        false
                )
        );

        ClaimEntity claim = context.claim();
        claim.setDocumentValidationStatus(validationStatus);
        claim.setDocumentValidationErrors(validationErrors);
        claim.setManualReviewRequired(manualReviewRequired);
        claim.setManualReviewReason(manualReviewRequired
            ? (validationErrors == null ? "Требуется проверка юристом" : validationErrors)
            : null);
        claim.setUsedSources(usedSources);
        claim.setValidationOverriddenAt(null);
        claim.setValidationOverriddenBy(null);
        claim.setValidationOverrideReason(null);
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);

        return new GenerateClaimResponse(
                version,
                generated.summaryForLawyer(),
                manualReviewRequired,
                List.copyOf(warnings),
                validationStatus,
                validationErrors,
                usedSources
        );
    }

    @Transactional(readOnly = true)
    protected GenerationContext loadContext(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = claimRepository.findByIdAndOrganizationId(claimId, user.organizationId())
                .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));

        ClaimParty creditor = partyRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(claim.getCreditorId(), user.organizationId())
                .orElseThrow(() -> ClaimException.validation("Не найдены данные кредитора"));

        ClaimParty debtor = partyRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(claim.getDebtorId(), user.organizationId())
                .orElseThrow(() -> ClaimException.validation("Не найдены данные должника"));

        ClaimContract contract = contractRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(claim.getContractId(), user.organizationId())
                .orElseThrow(() -> ClaimException.validation("Не найден договор претензии"));

        ClaimShipment shipment = shipmentRepository
                .findByIdAndOrganizationId(claim.getShipmentId(), user.organizationId())
                .orElseThrow(() -> ClaimException.validation("Не найдена перевозка претензии"));

        ClaimCalculation calculation = calculationRepository
                .findFirstByClaimIdOrderByCalculationVersionDesc(claimId)
                .orElseThrow(() -> ClaimException.validation("Сначала выполните расчёт претензии"));

        return new GenerationContext(claim, creditor, debtor, contract, shipment, calculation);
    }

    private void validateForGeneration(GenerationContext context) {
        if (context.claim().getStatus() != ClaimStatus.DRAFT
                && context.claim().getStatus() != ClaimStatus.PENDING_LEGAL_REVIEW) {
            throw ClaimException.conflict(
                    "Генерация доступна только для черновика или претензии на юридической проверке"
            );
        }
        if (isBlank(context.creditor().getName())) {
            throw ClaimException.validation("Не заполнено наименование кредитора");
        }
        if (isBlank(context.debtor().getName())) {
            throw ClaimException.validation("Не заполнено наименование должника");
        }
        if (!context.claim().isNonPaymentConfirmed()) {
            throw ClaimException.validation(
                    "Сначала бухгалтер должен подтвердить отсутствие оплаты"
            );
        }
        if (context.calculation().getRemainingDebt() == null
                || context.calculation().getRemainingDebt().signum() <= 0) {
            throw ClaimException.validation("Для генерации должна существовать непогашенная задолженность");
        }
        LocalDate overdueStartDate = context.calculation().getOverdueStartDate();
        if (overdueStartDate == null) {
            throw ClaimException.validation(
                    "Не удалось определить дату начала просрочки. Проверьте даты перевозки и условия оплаты, затем выполните расчёт повторно"
            );
        }
        if (LocalDate.now().isBefore(overdueStartDate)) {
            throw ClaimException.validation(
                    "Срок оплаты ещё не истёк. Формирование претензии будет доступно с "
                            + overdueStartDate.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
            );
        }
    }

    private void validateSignatory(CurrentClaimUser user) {
        if (user == null || isBlank(user.fullName())) {
            throw ClaimException.validation(
                    "В профиле пользователя не заполнено ФИО для подписи претензии. Войдите заново после заполнения профиля"
            );
        }
    }

    private void validateAiResponse(AiGenerateClaimResponse response) {
        if (response == null || response.generatedClaim() == null
                || isBlank(response.generatedClaim().claimText())) {
            throw ClaimException.conflict(
                    response != null && !Boolean.TRUE.equals(response.success())
                            ? "AI-модуль не смог сформировать проверяемый черновик претензии"
                            : "AI-модуль вернул пустой текст претензии"
            );
        }
    }

    private String buildUsedSources(
            ru.sber.cargotech.claim.client.dto.AiGenerateClaimRequest request,
            AiGenerateClaimResponse response
    ) {
        List<String> sources = new ArrayList<>();
        if (response.retrievedFragments() != null) {
            response.retrievedFragments().forEach(fragment -> sources.add(
                "DOCUMENT " + safe(fragment.documentId())
                    + " | CHUNK " + safe(fragment.chunkId())
                    + " | SCORE " + (fragment.score() == null ? "" : fragment.score())
                    + " | TEXT " + oneLine(fragment.text())
            ));
        }
        if (!sources.isEmpty()) {
            return joinSources(sources);
        }
        if (request.contractContext() != null) {
            request.contractContext().forEach(chunk -> sources.add(
                "CONTRACT " + safe(chunk.chunkId()) + " " + safe(chunk.clauseNumber())
            ));
        }
        if (request.legalContext() != null) {
            request.legalContext().forEach(item -> sources.add(
                "LAW " + safe(item.chunkId()) + " " + safe(item.lawCode()) + " " + safe(item.article())
            ));
        }
        return joinSources(sources);
    }

    private String joinSources(List<String> sources) {
        return sources.stream().map(String::trim).filter(value -> !value.isBlank()).distinct()
            .reduce((left, right) -> left + "\n" + right)
            .orElse(null);
    }

    private String oneLine(String value) {
        return safe(value).replaceAll("\\s+", " ").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<String> collectWarnings(AiGenerateClaimResponse response) {
        List<String> warnings = new ArrayList<>();
        addAll(warnings, response.ragWarnings());
        if (response.generatedClaim() != null) {
            addAll(warnings, response.generatedClaim().warnings());
        }
        if (response.guardrailResult() != null) {
            addAll(warnings, response.guardrailResult().warnings());
            addAll(warnings, response.guardrailResult().errors());
        }
        return warnings.stream().filter(value -> !isBlank(value)).distinct().toList();
    }

    private void addAll(List<String> target, List<String> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    protected record GenerationContext(
            ClaimEntity claim,
            ClaimParty creditor,
            ClaimParty debtor,
            ClaimContract contract,
            ClaimShipment shipment,
            ClaimCalculation calculation
    ) {}
}
