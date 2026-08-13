package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.client.AiClient;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimRequest;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimResponse;
import ru.sber.cargotech.claim.dto.ClaimVersionResponse;
import ru.sber.cargotech.claim.entity.*;
import ru.sber.cargotech.claim.enums.*;
import ru.sber.cargotech.claim.mapper.ClaimAiRequestMapper;
import ru.sber.cargotech.claim.repository.*;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaimGenerationMissingClauseManualReviewTest {

    @Test
    void missingVerifiedPaymentClauseForcesManualReviewWithoutInventingClause() {
        ClaimRepository claimRepository = mock(ClaimRepository.class);
        ClaimPartyRepository partyRepository = mock(ClaimPartyRepository.class);
        ClaimContractRepository contractRepository = mock(ClaimContractRepository.class);
        ClaimShipmentRepository shipmentRepository = mock(ClaimShipmentRepository.class);
        ClaimCalculationRepository calculationRepository = mock(ClaimCalculationRepository.class);
        ClaimAiRequestMapper requestMapper = mock(ClaimAiRequestMapper.class);
        AiClient aiClient = mock(AiClient.class);
        ClaimVersionService versionService = mock(ClaimVersionService.class);
        ClaimCalculationService calculationService = mock(ClaimCalculationService.class);
        ClaimContractClauseRepository clauseRepository = mock(ClaimContractClauseRepository.class);

        ClaimGenerationService service = new ClaimGenerationService(
                claimRepository,
                partyRepository,
                contractRepository,
                shipmentRepository,
                calculationRepository,
                requestMapper,
                aiClient,
                versionService,
                calculationService
        );
        service.setContractClauseRepository(clauseRepository);

        UUID claimId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID creditorId = UUID.randomUUID();
        UUID debtorId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();

        CurrentClaimUser user = new CurrentClaimUser(
                userId, orgId, "Павел", "Дмитриев", "Алексеевич", List.of("LAWYER")
        );

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(orgId);
        claim.setClaimNumber("NEG-CLAUSE-1");
        claim.setStatus(ClaimStatus.DRAFT);
        claim.setClaimType(ClaimType.PAYMENT_DELAY);
        claim.setCreditorId(creditorId);
        claim.setDebtorId(debtorId);
        claim.setContractId(contractId);
        claim.setShipmentId(shipmentId);
        claim.setNonPaymentConfirmed(true);

        ClaimParty creditor = new ClaimParty();
        creditor.setId(creditorId);
        creditor.setName("ООО Экспедитор");

        ClaimParty debtor = new ClaimParty();
        debtor.setId(debtorId);
        debtor.setName("ООО Клиент");

        ClaimContract contract = new ClaimContract();
        contract.setId(contractId);
        contract.setOrganizationId(orgId);
        contract.setClientId(debtorId);
        contract.setNumber("TEST-1");
        contract.setSignedAt(LocalDate.of(2026, 1, 1));

        ClaimShipment shipment = new ClaimShipment();
        shipment.setId(shipmentId);

        ClaimCalculation calculation = new ClaimCalculation();
        calculation.setClaimId(claimId);
        calculation.setRemainingDebt(new BigDecimal("100000.00"));
        calculation.setOverdueStartDate(LocalDate.now().minusDays(10));

        when(claimRepository.findByIdAndOrganizationId(claimId, orgId)).thenReturn(Optional.of(claim));
        when(partyRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(creditorId, orgId))
                .thenReturn(Optional.of(creditor));
        when(partyRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(debtorId, orgId))
                .thenReturn(Optional.of(debtor));
        when(contractRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(contractId, orgId))
                .thenReturn(Optional.of(contract));
        when(shipmentRepository.findByIdAndOrganizationId(shipmentId, orgId))
                .thenReturn(Optional.of(shipment));
        when(calculationRepository.findFirstByClaimIdOrderByCalculationVersionDesc(claimId))
                .thenReturn(Optional.of(calculation));

        ClaimContractClause unrelated = new ClaimContractClause();
        unrelated.setClauseNumber("10.2");
        unrelated.setClauseType(ClauseType.CLAIM_PROCEDURE);
        unrelated.setText("Срок ответа на претензию — 30 дней");
        unrelated.setActive(true);
        when(clauseRepository.findAllByContractIdAndActiveTrueOrderByClauseNumberAsc(contractId))
                .thenReturn(List.of(unrelated));

        AiGenerateClaimRequest aiRequest = mock(AiGenerateClaimRequest.class);
        when(requestMapper.map(
                same(claim), same(creditor), same(debtor), same(contract), same(shipment),
                same(calculation), same(user), anyList()
        )).thenReturn(aiRequest);

        when(aiClient.generate(same(aiRequest), eq(userId))).thenReturn(
                new AiGenerateClaimResponse(
                        true,
                        "PASSED",
                        List.of(),
                        new AiGenerateClaimResponse.GeneratedClaim(
                                "Черновик без выдуманного пункта оплаты.",
                                "Проверить договор вручную.",
                                List.of(),
                                false
                        ),
                        new AiGenerateClaimResponse.GuardrailResult("PASS", List.of(), List.of()),
                        List.of()
                )
        );

        ClaimVersionResponse version = new ClaimVersionResponse(
                UUID.randomUUID(),
                claimId,
                1,
                ClaimVersionSource.AI,
                null,
                "Черновик без выдуманного пункта оплаты.",
                "Черновик",
                false,
                userId,
                OffsetDateTime.now()
        );
        when(versionService.create(eq(user), eq(claimId), any())).thenReturn(version);

        var result = service.generate(user, claimId);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("Не найден пункт договора"));
        assertThat(claim.isManualReviewRequired()).isTrue();
        assertThat(claim.getManualReviewReason()).contains("Не найден пункт договора");
        verify(aiClient).generate(same(aiRequest), eq(userId));
    }
}
