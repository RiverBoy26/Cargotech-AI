package ru.sber.cargotech.ai.claim;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimGenerationPipelineContractContextTest {

    @Test
    void confirmedDatabaseClauseWinsOverRagCopyOfSameClause() {
        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
            null, null, null, null, null, null
        );
        var verified = new GenerateClaimRequest.ContractContextChunk(
            "db-clause-8-2", "8.2", "Подтверждённый пункт", "Проверенный пользователем текст"
        );
        var ragDuplicate = new GenerateClaimRequest.ContractContextChunk(
            "rag-clause-8-2", " 8.2 ", "Оригинал договора", "Непроверенная копия из RAG"
        );
        var ragGeneric = new GenerateClaimRequest.ContractContextChunk(
            "rag-general", null, "Общие условия", "Дополняющий ненумерованный фрагмент"
        );

        List<GenerateClaimRequest.ContractContextChunk> merged = service.mergeContractContext(
            List.of(verified), List.of(ragDuplicate, ragGeneric)
        );

        assertThat(merged).containsExactly(verified, ragGeneric);
    }

    @Test
    void normalizesMismatchedContractChunkIdFromUniqueClauseNumber() {
        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                null, null, null, null, null, null
        );

        var request = new GenerateClaimRequest(
                null,
                null,
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "contract-clause-8-2", "8.2", "Оплата", "Срок оплаты"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "contract-clause-8-4", "8.4", "Расчеты", "Платежные дни"
                        )
                ),
                List.of(),
                null,
                List.of()
        );

        var response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "Согласно п. 8.2 Договора срок оплаты составляет 45 дней.",
                "summary",
                List.of(new GenerateClaimResponse.UsedContractClause(
                        "8.2",
                        "contract-clause-8-4",
                        "срок оплаты"
                )),
                List.of(),
                null,
                List.of(),
                List.of(),
                true
        );

        GenerateClaimResponse normalized = service.normalizeCitationMetadata(request, response);

        assertThat(normalized.usedContractClauses()).containsExactly(
                new GenerateClaimResponse.UsedContractClause(
                        "8.2",
                        "contract-clause-8-2",
                        "срок оплаты"
                )
        );
    }

    @Test
    void leavesAmbiguousUnknownContractCitationForGuardrail() {
        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                null, null, null, null, null, null
        );

        var request = new GenerateClaimRequest(
                null,
                null,
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "contract-a", "8.2", "Оплата", "Первая копия"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "contract-b", "8.2", "Оплата", "Вторая копия"
                        )
                ),
                List.of(),
                null,
                List.of()
        );

        var used = new GenerateClaimResponse.UsedContractClause(
                "8.2",
                "unknown-chunk",
                "срок оплаты"
        );
        var response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "Согласно п. 8.2 Договора срок оплаты составляет 45 дней.",
                "summary",
                List.of(used),
                List.of(),
                null,
                List.of(),
                List.of(),
                true
        );

        GenerateClaimResponse normalized = service.normalizeCitationMetadata(request, response);

        assertThat(normalized.usedContractClauses()).containsExactly(used);
    }

    @Test
    void suppressesEmptyRagWarningsWhenTrustedFallbackContextFilledTheFinalRequest() {
        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                null, null, null, null, null, null
        );
        var request = new GenerateClaimRequest(
                null,
                null,
                List.of(new GenerateClaimRequest.ContractContextChunk(
                        "contract-8-2", "8.2", "Оплата", "PAYMENT_TERMS", "Срок оплаты"
                )),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "law-395", "ГК РФ", "395", "проценты",
                        "Проценты за пользование чужими денежными средствами",
                        "ст. 395 ГК РФ", "2026-08-15", "PAYMENT_DELAY"
                )),
                new GenerateClaimRequest.TemplateContext(
                        "template-payment", "Претензия", GenerateClaimRequest.ClaimType.PAYMENT_DELAY, List.of("Требования")
                ),
                List.of()
        );
        var warnings = new java.util.ArrayList<String>();

        service.addEffectiveRagWarnings(
                warnings,
                List.of("contract_context is empty", "legal_context is empty", "template_context is empty"),
                request
        );

        assertThat(warnings).isEmpty();
    }

    @Test
    void keepsEmptyRagWarningsWhenFinalContextIsStillMissing() {
        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                null, null, null, null, null, null
        );
        var request = new GenerateClaimRequest(null, null, List.of(), List.of(), null, List.of());
        var warnings = new java.util.ArrayList<String>();

        service.addEffectiveRagWarnings(
                warnings,
                List.of("contract_context is empty", "legal_context is empty", "template_context is empty"),
                request
        );

        assertThat(warnings).containsExactly(
                "contract_context is empty",
                "legal_context is empty",
                "template_context is empty"
        );
    }

    @Test
    void repairBudgetLeavesMarginBelowSixtySeconds() {
        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                null, null, null, null, null, null
        );

        assertThat(service.initialTimeoutMillis(0)).isEqualTo(50_000);
        assertThat(service.initialTimeoutMillis(10_000)).isEqualTo(42_000);
        assertThat(service.initialTimeoutMillis(53_000)).isZero();

        assertThat(service.repairTimeoutMillis(20_000)).isEqualTo(27_000);
        assertThat(service.repairTimeoutMillis(30_000)).isEqualTo(22_000);
        assertThat(service.repairTimeoutMillis(33_000)).isZero();
    }

    @Test
    void deterministicCitationRepairDoesNotRewriteDraftForUnrelatedGuardrailError() {
        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                null, null, null, null, null, null
        );
        GenerateClaimRequest request = new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "claim-1",
                        GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        null,
                        null,
                        new GenerateClaimRequest.ContractFacts("АН-ТЭ/2026-013", "2026-03-31"),
                        null,
                        null,
                        "2026-08-15"
                ),
                null,
                List.of(new GenerateClaimRequest.ContractContextChunk(
                        "pay-8-2", "8.2", "Оплата", "PAYMENT_TERMS", "45 календарных дней"
                )),
                List.of(),
                null,
                List.of()
        );
        GenerateClaimResponse response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "Оплата должна быть произведена согласно п. 8.2.",
                "summary",
                List.of(new GenerateClaimResponse.UsedContractClause(
                        "8.2", "pay-8-2", "срок оплаты"
                )),
                List.of(),
                null,
                List.of(),
                List.of(),
                true
        );

        GenerateClaimResponse result = service.repairContractCitations(
                request,
                response,
                List.of("Model changed total_amount")
        );

        assertThat(result).isSameAs(response);
    }

}
