package ru.sber.cargotech.ai.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SampleRagChunksFactoryTest {

    private final SampleRagChunksFactory factory = new SampleRagChunksFactory();

    @Test
    void legalPaymentChunksAreAutoUseAndContainArticle395() {
        var legal = factory.paymentDelayChunks().stream()
                .filter(chunk -> chunk.ragCollection() == RagCollection.LEGAL_CONTEXT)
                .toList();

        assertThat(legal).isNotEmpty();
        assertThat(legal).allSatisfy(chunk ->
                assertThat(chunk.extra()).containsEntry("auto_use", true)
        );
        assertThat(legal).anySatisfy(chunk -> {
            assertThat(chunk.extra()).containsEntry("article", "395");
            assertThat(chunk.citation()).contains("395");
        });
    }

    @Test
    void paymentTemplateDoesNotRequestAttachments() {
        var template = factory.paymentDelayChunks().stream()
                .filter(chunk -> chunk.ragCollection() == RagCollection.TEMPLATE_CONTEXT)
                .findFirst()
                .orElseThrow();

        assertThat(template.text()).contains("Приложения и банковские реквизиты в текущем scope не формируются");
        assertThat(template.extra().get("template_structure").toString()).doesNotContain("Приложения");
    }
}
