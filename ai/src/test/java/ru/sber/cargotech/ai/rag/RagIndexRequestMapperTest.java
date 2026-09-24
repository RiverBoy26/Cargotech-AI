package ru.sber.cargotech.ai.rag;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagIndexRequestMapperTest {

    private final RagIndexRequestMapper mapper = new RagIndexRequestMapper();

    @Test
    void requiresClientIdForContractChunks() {
        IndexRagChunksRequest request = request(contractChunk(null, "Срок оплаты составляет 30 дней."));

        assertThatThrownBy(() -> mapper.toChunks(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client_id is required");
    }

    @Test
    void rejectsRawPiiInsideIndexedText() {
        IndexRagChunksRequest request = request(contractChunk(
                "client-A",
                "Контакт водителя: +7 999 123-45-67, автомобиль А123ВС77."
        ));

        assertThatThrownBy(() -> mapper.toChunks(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("raw phone")
                .hasMessageContaining("vehicle number");
    }

    @Test
    void allowsOpaqueNumericClientAndContractIdentifiers() {
        IndexRagChunksRequest request = request(new IndexRagChunksRequest.IndexRagChunk(
                "chunk-1",
                RagCollection.CONTRACT_CONTEXT,
                RagChunkType.PAYMENT_TERM,
                "PAYMENT_DELAY",
                "7701234567",
                "contract-7701234567",
                "45/2026",
                "10.01.2026",
                "EXPEDITOR_TO_CLIENT",
                "CLIENT_CONTRACT",
                "source-1",
                "Обезличенный договор",
                "Порядок расчетов",
                "4",
                "4.2",
                "PAYMENT_TERM",
                "Оплата производится в течение 30 календарных дней.",
                "п. 4.2 договора",
                true,
                Map.of()
        ));

        List<RagChunk> chunks = mapper.toChunks(request);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).clientId()).isEqualTo("7701234567");
    }

    @Test
    void allowsUnnumberedOriginalContractChunkWithoutFakeClause() {
        IndexRagChunksRequest request = request(new IndexRagChunksRequest.IndexRagChunk(
                "chunk-general",
                RagCollection.CONTRACT_CONTEXT,
                RagChunkType.CONTRACT_GENERAL,
                "ALL",
                "org-A",
                "client-A",
                "contract-A",
                "45/2026",
                null,
                "EXPEDITOR_TO_CLIENT",
                "CLIENT_CONTRACT",
                "source-1",
                "Договор № 45/2026",
                "Общие положения",
                "Общие положения",
                null,
                "Условия договора",
                "Стороны согласовали общие условия сотрудничества.",
                "раздел «Общие положения» Договора № 45/2026",
                true,
                Map.of(
                    "source_kind", "original_contract",
                    "organization_id", "org-B",
                    "contract_id", "contract-B",
                    "is_current", false
                )
        ));

        assertThat(mapper.toChunks(request)).singleElement().satisfies(chunk -> {
            assertThat(chunk.organizationId()).isEqualTo("org-A");
            assertThat(chunk.clauseNumber()).isNull();
            assertThat(chunk.toPayload())
                .containsEntry("organization_id", "org-A")
                .containsEntry("contract_id", "contract-A")
                .containsEntry("is_current", true);
        });
    }

    private IndexRagChunksRequest request(IndexRagChunksRequest.IndexRagChunk chunk) {
        return new IndexRagChunksRequest("batch-1", "TEST", List.of(chunk));
    }

    private IndexRagChunksRequest.IndexRagChunk contractChunk(String clientId, String text) {
        return new IndexRagChunksRequest.IndexRagChunk(
                "chunk-1",
                RagCollection.CONTRACT_CONTEXT,
                RagChunkType.PAYMENT_TERM,
                "PAYMENT_DELAY",
                clientId,
                "contract-1",
                "45/2026",
                "10.01.2026",
                "EXPEDITOR_TO_CLIENT",
                "CLIENT_CONTRACT",
                "source-1",
                "Обезличенный договор",
                "Порядок расчетов",
                "4",
                "4.2",
                "PAYMENT_TERM",
                text,
                "п. 4.2 договора",
                true,
                Map.of()
        );
    }
}
