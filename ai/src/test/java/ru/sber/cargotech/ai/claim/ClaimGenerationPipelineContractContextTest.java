package ru.sber.cargotech.ai.claim;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;

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
}
