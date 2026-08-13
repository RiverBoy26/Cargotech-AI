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

}
