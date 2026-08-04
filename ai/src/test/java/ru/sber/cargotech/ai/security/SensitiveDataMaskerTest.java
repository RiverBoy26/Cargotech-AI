package ru.sber.cargotech.ai.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void masksPersonalAndCommercialDataInJsonAndText() {
        String input = """
                {"name":"ООО Тест","inn":"7701234567","legal_address":"г. Москва, ул. Ленина, 1",
                "principal_debt":240000,"phone":"+7 999 123-45-67","email":"lawyer@example.ru"}
                Оплатить 240 000 руб. Автомобиль А123ВС77.
                """;

        String masked = masker.mask(input);

        assertThat(masked)
                .doesNotContain("ООО Тест")
                .doesNotContain("7701234567")
                .doesNotContain("ул. Ленина")
                .doesNotContain("240000")
                .doesNotContain("240 000 руб")
                .doesNotContain("+7 999 123-45-67")
                .doesNotContain("lawyer@example.ru")
                .doesNotContain("А123ВС77")
                .contains("***")
                .contains("[AMOUNT]");
    }
}
