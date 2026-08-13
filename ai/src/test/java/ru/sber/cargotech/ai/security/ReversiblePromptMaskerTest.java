package ru.sber.cargotech.ai.security;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReversiblePromptMaskerTest {

    private final ReversiblePromptMasker masker = new ReversiblePromptMasker();

    @Test
    void masksPiiBeforeProviderButKeepsLegalAmountsDatesAndContractFacts() {
        String prompt = """
                {
                  "creditor": {"name":"ООО Экспедитор","inn":"7701001123","legal_address":"г. Санкт-Петербург, ул. Ленина, 1"},
                  "debtor": {"name":"ООО Альфа Тест","inn":"7705123456","legal_address":"г. Москва, ул. Тестовая, д. 10"},
                  "principal_debt": 250000.00,
                  "contract_number": "АН-ТЭ/2026-013",
                  "claim_date": "2026-08-13"
                }
                Использовать ст. 395 ГК РФ, сумма 250 000 рублей 00 копеек.
                """;

        var masked = masker.mask(List.of(new GigaChatMessage("user", prompt)));
        String providerPrompt = masked.messages().getFirst().content();

        assertThat(providerPrompt)
                .doesNotContain("ООО Экспедитор")
                .doesNotContain("7701001123")
                .doesNotContain("ул. Ленина")
                .doesNotContain("ООО Альфа Тест")
                .contains("250000.00")
                .contains("250 000 рублей 00 копеек")
                .contains("АН-ТЭ/2026-013")
                .contains("2026-08-13")
                .contains("ст. 395 ГК РФ")
                .contains("__CT_PII_");
    }

    @Test
    void restoresOnlyRequestLocalPlaceholdersInProviderResponse() {
        var masked = masker.mask(List.of(new GigaChatMessage(
                "user",
                "{\"name\":\"Дмитриев Павел Алексеевич\",\"email\":\"lawyer@example.ru\"}"
        )));
        String providerPrompt = masked.messages().getFirst().content();
        String personPlaceholder = masked.reverseMap().entrySet().stream()
                .filter(entry -> entry.getKey().contains("NAME"))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        String emailPlaceholder = masked.reverseMap().entrySet().stream()
                .filter(entry -> entry.getKey().contains("EMAIL"))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        GigaChatChatResponse response = new GigaChatChatResponse(
                List.of(new GigaChatChatResponse.Choice(
                        0,
                        new GigaChatMessage("assistant", "Подписант: " + personPlaceholder + ", email: " + emailPlaceholder)
                )),
                new GigaChatChatResponse.Usage(10, 5, 15)
        );

        GigaChatChatResponse restored = masked.restore(response);
        assertThat(providerPrompt).doesNotContain("Дмитриев Павел Алексеевич");
        assertThat(restored.firstContent())
                .contains("Дмитриев Павел Алексеевич")
                .contains("lawyer@example.ru")
                .doesNotContain("__CT_PII_");
    }

    @Test
    void rejectsUnknownPlaceholderReturnedByModel() {
        var masked = masker.mask(List.of(new GigaChatMessage("user", "{\"name\":\"ООО Тест\"}")));

        assertThatThrownBy(() -> masked.restore("Ответ __CT_PII_NAME_999__"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved sensitive-data placeholder");
    }
}
