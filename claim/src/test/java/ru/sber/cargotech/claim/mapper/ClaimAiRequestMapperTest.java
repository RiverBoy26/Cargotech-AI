package ru.sber.cargotech.claim.mapper;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimRequest;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ClaimType;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;
import ru.sber.cargotech.claim.enums.ContractRagStatus;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.enums.TermDayType;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimAiRequestMapperTest {

    private final ClaimAiRequestMapper mapper = new ClaimAiRequestMapper();

    @Test
    void mapsClaimHeaderDeadlineSignatoryAndStructuredContractTerms() {
        UUID contractId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();

        ClaimEntity claim = new ClaimEntity();
        claim.setId(UUID.randomUUID());
        claim.setClaimNumber("CLM-2026-001");
        claim.setClaimType(ClaimType.PAYMENT_DELAY);
        claim.setNonPaymentConfirmed(true);

        ClaimParty creditor = party("ООО Экспедитор", "7701001123", "Санкт-Петербург");
        ClaimParty debtor = party("ООО Клиент", "7705123456", "Москва");

        ClaimContract contract = new ClaimContract();
        contract.setId(contractId);
        contract.setOrganizationId(userOrganizationId());
        contract.setClientId(clientId);
        UUID documentId = UUID.randomUUID();
        contract.setDocumentId(documentId);
        contract.setRagSourceDocumentId(documentId);
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setExtractionStatus(ContractExtractionStatus.CONFIRMED);
        contract.setRagIndexStatus(ContractRagStatus.INDEXED);
        contract.setNumber("ДО-2026-001");
        contract.setSignedAt(LocalDate.of(2026, 7, 1));
        contract.setPaymentDays(5);
        contract.setPaymentStartEvent(PaymentStartEvent.ACT_SIGNED);
        contract.setPenaltyType(PenaltyType.NONE);
        contract.setClaimResponseDays(14);
        contract.setClaimResponseDayType(TermDayType.WORKING_DAYS);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setOrderNumber("РЕЙС-2026-001");
        shipment.setRouteFrom("Новосибирск");
        shipment.setRouteTo("Москва");
        shipment.setLoadingDate(LocalDate.of(2026, 7, 18));
        shipment.setActSignedAt(LocalDate.of(2026, 7, 20));
        shipment.setCurrency("RUB");

        ClaimCalculation calculation = new ClaimCalculation();
        calculation.setRemainingDebt(new BigDecimal("100000.00"));
        calculation.setPenaltyType(PenaltyType.NONE);
        calculation.setPenaltyAmount(BigDecimal.ZERO);
        calculation.setTotalAmount(new BigDecimal("100000.00"));
        calculation.setOverdueDays(11);
        calculation.setOverdueStartDate(LocalDate.of(2026, 7, 26));
        calculation.setFormula("100000 + 0");

        CurrentClaimUser user = new CurrentClaimUser(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Павел",
                "Дмитриев",
                "Алексеевич",
                List.of("LAWYER")
        );

        AiGenerateClaimRequest result = mapper.map(
                claim, creditor, debtor, contract, shipment, calculation, user
        );

        assertThat(result.caseFacts().claimNumber()).isEqualTo("CLM-2026-001");
        assertThat(result.caseFacts().contract().claimResponseDays()).isEqualTo(14);
        assertThat(result.caseFacts().contract().claimResponseDayType())
                .isEqualTo(AiGenerateClaimRequest.TermDayType.WORKING_DAYS);
        assertThat(result.caseFacts().payment().paymentDueDate()).isEqualTo("2026-07-25");
        assertThat(result.caseFacts().signatory().name()).isEqualTo("Дмитриев Павел Алексеевич");
        assertThat(result.caseFacts().signatory().position()).isEqualTo("Юрист");
        assertThat(result.caseFacts().shipment().actNumber()).isNull();
        assertThat(result.caseFacts().shipment().actDate()).isEqualTo("2026-07-20");
        assertThat(result.contractContext())
                .extracting(AiGenerateClaimRequest.ContractContextChunk::sectionTitle)
                .containsExactlyInAnyOrder(
                        "Структурированные условия оплаты",
                        "Структурированные условия ответственности",
                        "Структурированный срок ответа на претензию"
                );
        assertThat(result.contractContext())
                .extracting(AiGenerateClaimRequest.ContractContextChunk::clauseType)
                .containsExactlyInAnyOrder("PAYMENT_TERMS", "PENALTY", "CLAIM_PROCEDURE");
        assertThat(result.ragOptions().organizationId()).isEqualTo(contract.getOrganizationId().toString());
        assertThat(result.ragOptions().enabled()).isTrue();
        assertThat(result.templateContext().templateStructure())
                .contains(
                        "Исходящий номер и дата претензии",
                        "Перечень приложений",
                        "Подпись представителя кредитора с основанием полномочий"
                );

        contract.setRagIndexStatus(ContractRagStatus.FAILED);
        AiGenerateClaimRequest afterIndexFailure = mapper.map(
                claim, creditor, debtor, contract, shipment, calculation, user
        );
        assertThat(afterIndexFailure.ragOptions().enabled()).isFalse();
    }


    @Test
    void contractPenaltyAddsArticle330ButNotArticle395OrGenericArticle310() {
        ClaimEntity claim = new ClaimEntity();
        claim.setId(UUID.randomUUID());
        claim.setClaimNumber("CLM-PENALTY");
        claim.setClaimType(ClaimType.PAYMENT_DELAY);
        claim.setNonPaymentConfirmed(true);

        ClaimContract contract = new ClaimContract();
        contract.setId(UUID.randomUUID());
        contract.setOrganizationId(userOrganizationId());
        contract.setClientId(UUID.randomUUID());
        contract.setNumber("P-1");
        contract.setSignedAt(LocalDate.of(2026, 7, 1));
        contract.setPaymentDays(30);
        contract.setPaymentStartEvent(PaymentStartEvent.ACT_SIGNED);
        contract.setPenaltyType(PenaltyType.CONTRACT_PENALTY);
        contract.setPenaltyRate(new BigDecimal("0.1"));
        contract.setClaimResponseDays(20);
        contract.setClaimResponseDayType(TermDayType.CALENDAR_DAYS);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setOrderNumber("R-1");
        shipment.setActSignedAt(LocalDate.of(2026, 7, 2));
        shipment.setCurrency("RUB");

        ClaimCalculation calculation = new ClaimCalculation();
        calculation.setPrincipalDebt(new BigDecimal("90000"));
        calculation.setPaidAmount(BigDecimal.ZERO);
        calculation.setRemainingDebt(new BigDecimal("90000"));
        calculation.setPenaltyType(PenaltyType.CONTRACT_PENALTY);
        calculation.setPenaltyRate(new BigDecimal("0.1"));
        calculation.setPenaltyAmount(new BigDecimal("2520"));
        calculation.setTotalAmount(new BigDecimal("92520"));
        calculation.setOverdueDays(28);
        calculation.setOverdueStartDate(LocalDate.of(2026, 7, 17));
        calculation.setFormula("90000 × 0,1% × 28");

        AiGenerateClaimRequest result = mapper.map(
                claim,
                party("ООО Экспедитор", "7812456730", "Санкт-Петербург"),
                party("ООО Клиент", "6319245078", "Самара"),
                contract,
                shipment,
                calculation,
                new CurrentClaimUser(
                        UUID.randomUUID(), userOrganizationId(),
                        "Иван", "Иванов", "Иванович", List.of("LAWYER")
                )
        );

        assertThat(result.legalContext())
                .extracting(AiGenerateClaimRequest.LegalContextItem::article)
                .contains("309", "314 п. 1", "330")
                .doesNotContain("310", "395", "801");
    }

    private ClaimParty party(String name, String inn, String address) {
        ClaimParty party = new ClaimParty();
        party.setName(name);
        party.setInn(inn);
        party.setLegalAddress(address);
        return party;
    }

    private UUID userOrganizationId() {
        return UUID.fromString("10000000-0000-0000-0000-000000000001");
    }
}
