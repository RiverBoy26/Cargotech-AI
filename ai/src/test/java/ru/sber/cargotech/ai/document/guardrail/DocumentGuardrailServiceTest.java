package ru.sber.cargotech.ai.document.guardrail;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailDecision;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentGuardrailServiceTest {

    private final DocumentGuardrailService service = new DocumentGuardrailService();

    @Test
    void passesFactuallyConsistentNotification() {
        GuardrailResult result = service.check(
                request(true),
                response(validText(), GenerateClaimResponse.DocumentType.NOTIFICATION),
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void acceptsRussianLongDateButBlocksUnknownInnAndMoneyDemand() {
        String text = validText()
                .replace("10.06.2026", "10 июня 2026 года")
                + " ИНН третьего лица 7812345678. Требуем оплатить 20 000 руб.";

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.NOTIFICATION),
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("unknown INN"));
        assertThat(result.errors()).anyMatch(error -> error.contains("monetary amount"));
        assertThat(result.errors()).anyMatch(error -> error.contains("monetary demand"));
        assertThat(result.errors()).noneMatch(error -> error.contains("loading_date"));
    }

    @Test
    void blocksUnconfirmedLoadingFailure() {
        GuardrailResult result = service.check(
                request(false),
                response(validText(), GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("confirmed by dispatcher"));
    }

    private GenerateClaimRequest request(boolean confirmed) {
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "lf-doc-1",
                        GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                        new GenerateClaimRequest.Party("ООО Экспедитор", "7800000000", "Санкт-Петербург"),
                        new GenerateClaimRequest.Party("ООО Перевозчик", "7700000000", "Москва"),
                        new GenerateClaimRequest.ContractFacts("LF-1", "01.06.2026"),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-1", "Москва — Тверь", null, null, null, null,
                                "10.06.2026", "г. Москва, Складская улица, 1", "09:00–11:00",
                                "тент 20 тонн", "ООО Перевозчик", confirmed
                        ),
                        null,
                        "10.06.2026"
                ),
                new GenerateClaimRequest.BackendCalculation(
                        BigDecimal.ZERO,
                        GenerateClaimRequest.PenaltyType.NONE,
                        null,
                        0,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "RUB",
                        null
                ),
                List.of(new GenerateClaimRequest.ContractContextChunk(
                        "lf-duty", "5.1", "Подача ТС", "Перевозчик обязан предоставить транспортное средство"
                )),
                List.of(),
                null,
                List.of()
        );
    }

    private GenerateDocumentResponse response(String text, GenerateClaimResponse.DocumentType type) {
        return new GenerateDocumentResponse(
                type,
                type == GenerateClaimResponse.DocumentType.NOTIFICATION
                        ? "Уведомление о составлении акта"
                        : "Акт о непредоставлении транспортного средства",
                text,
                "Факт срыва погрузки зафиксирован",
                List.of(new GenerateClaimResponse.UsedContractClause("5.1", "lf-duty", "обязанность подачи")),
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                        "Заявка ORD-1",
                        true
                )),
                List.of(),
                true
        );
    }

    private String validText() {
        return """
                От: ООО Экспедитор, ИНН 7800000000.
                Кому: ООО Перевозчик, ИНН 7700000000.
                По договору LF-1 и заявке ORD-1 транспортное средство не предоставлено.
                Погрузка была назначена на 10.06.2026 по адресу: г. Москва, Складская улица, 1,
                во временной интервал 09:00–11:00, маршрут Москва — Тверь.
                Настоящим уведомляем о составлении акта о непредоставлении транспортного средства.
                """;
    }
}
