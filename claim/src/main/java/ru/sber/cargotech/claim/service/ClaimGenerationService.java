package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.mapper.ClaimAiRequestMapper;
import ru.sber.cargotech.claim.repository.*;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

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

    public GenerateClaimResponse generate(CurrentClaimUser user, UUID claimId) {
        GenerationContext context = loadContext(user, claimId);
        validateForGeneration(context);

        log.info("Запуск AI-генерации: claimId={}, organizationId={}, userId={}",
                claimId, user.organizationId(), user.userId());

        AiGenerateClaimResponse aiResponse = aiClient.generate(requestMapper.map(
                context.claim(),
                context.creditor(),
                context.debtor(),
                context.contract(),
                context.shipment(),
                context.calculation()
        ));

        if (aiResponse.guardrailResult() != null) {
            log.warn(
                    "AI guardrail: claimId={}, success={}, decision={}, errors={}, warnings={}, ragWarnings={}",
                    claimId,
                    aiResponse.success(),
                    aiResponse.guardrailResult().decision(),
                    aiResponse.guardrailResult().errors(),
                    aiResponse.guardrailResult().warnings(),
                    aiResponse.ragWarnings()
            );
        }

        validateAiResponse(aiResponse);
        AiGenerateClaimResponse.GeneratedClaim generated = aiResponse.generatedClaim();

        ClaimVersionResponse version = versionService.create(
                user,
                claimId,
                new CreateClaimVersionRequest(
                        ClaimVersionSource.AI,
                        null,
                        generated.claimText(),
                        Boolean.TRUE.equals(generated.manualReviewRequired())
                                ? "Черновик сформирован AI. Требуется ручная проверка."
                                : "Черновик автоматически сформирован AI.",
                        false
                )
        );

        return new GenerateClaimResponse(
                version,
                generated.summaryForLawyer(),
                Boolean.TRUE.equals(generated.manualReviewRequired()),
                collectWarnings(aiResponse)
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
    }

    private void validateAiResponse(AiGenerateClaimResponse response) {
        if (!Boolean.TRUE.equals(response.success())) {
            throw ClaimException.conflict("AI-модуль не смог сформировать претензию");
        }
        if (response.generatedClaim() == null || isBlank(response.generatedClaim().claimText())) {
            throw ClaimException.conflict("AI-модуль вернул пустой текст претензии");
        }
        if (response.guardrailResult() != null
                && "BLOCK".equalsIgnoreCase(response.guardrailResult().decision())) {
            throw ClaimException.conflict("Сформированный текст не прошёл проверку AI-модуля");
        }
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
