package ru.sber.cargotech.ai.claim.prompt;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentDelayPromptBuilderTest {

    @Test
    void repairPromptKeepsOnlyRelevantContextAndDropsExamplesAndTemplate() {
        PaymentDelayPromptBuilder builder = new PaymentDelayPromptBuilder(new ObjectMapper());
        GenerateClaimRequest request = new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "claim-1",
                        GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        new GenerateClaimRequest.Party("ООО Экспедитор", "7812456730", "СПб"),
                        new GenerateClaimRequest.Party("АО Клиент", "2224186305", "Барнаул"),
                        new GenerateClaimRequest.ContractFacts(
                                "АН-ТЭ/2026-013",
                                "2026-03-31",
                                30,
                                GenerateClaimRequest.TermDayType.CALENDAR_DAYS,
                                "document-1"
                        ),
                        new GenerateClaimRequest.ShipmentFacts(
                                "РЕЙС-1", "Барнаул — Новосибирск", null, "2026-05-30", null, null
                        ),
                        new GenerateClaimRequest.PaymentFacts(
                                "2026-07-16", GenerateClaimRequest.PaymentStatus.UNPAID, true
                        ),
                        "2026-08-15"
                ),
                new GenerateClaimRequest.BackendCalculation(
                        new BigDecimal("100000.00"),
                        GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                        "по периодам ключевой ставки",
                        29,
                        new BigDecimal("1119.18"),
                        new BigDecimal("101119.18"),
                        "RUB",
                        "formula",
                        "2026-07-17",
                        "2026-08-14"
                ),
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "pay-8-2", "8.2", "Оплата", "PAYMENT_TERMS", "45 календарных дней"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "claim-10-2", "10.2", "Претензии", "CLAIM_PROCEDURE", "30 календарных дней"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "jurisdiction-10-3", "10.3", "Споры", "JURISDICTION", "Очень длинная подсудность"
                        )
                ),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "law-395", "ГК РФ", "395", "проценты", "Правило статьи 395",
                        "ст. 395 ГК РФ", "2026-08-15", "PAYMENT_DELAY"
                )),
                new GenerateClaimRequest.TemplateContext(
                        "template-verbose", "Большой шаблон", GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        List.of("Очень длинная структура шаблона")
                ),
                List.of(new GenerateClaimRequest.SimilarExample(
                        "example-verbose", GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        "не копировать факты", "Очень длинный пример"
                ))
        );
        GenerateClaimResponse blocked = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "Оплата согласно п. 8.2.",
                "summary",
                List.of(new GenerateClaimResponse.UsedContractClause("8.2", "pay-8-2", "срок оплаты")),
                List.of(),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("100000.00"),
                        GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                        new BigDecimal("1119.18"),
                        new BigDecimal("101119.18"),
                        29,
                        "RUB"
                ),
                List.of(),
                List.of(),
                true
        );

        String repairUserPrompt = builder.buildRepair(
                request,
                blocked,
                List.of("claim_text contract clause citation must include contract number in the same sentence: 8.2")
        ).get(1).content();

        assertThat(repairUserPrompt)
                .contains("pay-8-2")
                .contains("claim-10-2")
                .contains("law-395")
                .doesNotContain("jurisdiction-10-3")
                .doesNotContain("Очень длинная подсудность")
                .doesNotContain("template-verbose")
                .doesNotContain("example-verbose")
                .doesNotContain("\n  \"case_facts\"");
    }
}
