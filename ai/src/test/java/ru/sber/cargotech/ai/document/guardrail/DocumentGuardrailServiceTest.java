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


    @Test
    void acceptsEquivalentAddressAndTimeWording() {
        String text = validText()
                .replace("по адресу: г. Москва, Складская улица, 1", "на Складской улице, дом 1, в Москве")
                .replace("во временной интервал 09:00–11:00", "в период с 09:00 до 11:00");

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.NOTIFICATION),
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksDifferentAddressOrTimeWindow() {
        String text = validText()
                .replace("Складская улица, 1", "Складская улица, 2")
                .replace("09:00–11:00", "10:00–12:00");

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("loading_address"));
        assertThat(result.errors()).anyMatch(error -> error.contains("loading_time_window"));
    }

    @Test
    void notificationMayOmitPartyInnsButStillBlocksUnknownInn() {
        String text = validText()
                .replace(", ИНН 7800000000", "")
                .replace(", ИНН 7700000000", "");

        GuardrailResult pass = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.NOTIFICATION),
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );
        GuardrailResult block = service.check(
                request(true),
                response(text + " ИНН третьего лица 7812345678.", GenerateClaimResponse.DocumentType.NOTIFICATION),
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(pass.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(pass.errors()).isEmpty();
        assertThat(block.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(block.errors()).anyMatch(error -> error.contains("unknown INN"));
    }

    @Test
    void actAcceptsOrderNumberOnlyInMatchingTransportOrderAttachment() {
        String text = validText().replace("и заявке ORD-1 ", "");

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksWrongOrderNumberInAttachmentWhenTextOmitsOrderNumber() {
        String text = validText().replace("и заявке ORD-1 ", "");
        GenerateDocumentResponse base = response(text, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT);
        GenerateDocumentResponse wrongAttachment = new GenerateDocumentResponse(
                base.documentType(), base.documentTitle(), base.documentText(), base.summaryForLawyer(),
                base.usedContractClauses(),
                base.usedLawArticles(),
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                        "Заявка ORD-999",
                        true
                )),
                base.warnings(), base.manualReviewRequired()
        );

        GuardrailResult result = service.check(
                request(true),
                wrongAttachment,
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("shipment.order_number"));
        assertThat(result.errors()).anyMatch(error -> error.contains("TRANSPORT_ORDER attachment does not match"));
    }

    @Test
    void blocksInventedRepresentativesAndDispatcherPartyAttribution() {
        String text = validText()
                + " Мы, нижеподписавшиеся представители сторон, составили настоящий акт."
                + " Факт подтвержден диспетчером ООО Перевозчик.";

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("invents representatives"));
        assertThat(result.errors()).anyMatch(error -> error.contains("attributes dispatcher"));
    }

    @Test
    void blocksUntraceableLegalReferenceAndAcceptsVerifiedLegalChunk() {
        String text = validText() + " Основание: ст. 330 ГК РФ.";
        GenerateDocumentResponse withoutTrace = response(
                text,
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        GuardrailResult block = service.check(
                request(true),
                withoutTrace,
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        GenerateClaimRequest legalRequest = requestWithLegalContext();
        GenerateDocumentResponse traced = new GenerateDocumentResponse(
                withoutTrace.documentType(),
                withoutTrace.documentTitle(),
                withoutTrace.documentText(),
                withoutTrace.summaryForLawyer(),
                withoutTrace.usedContractClauses(),
                List.of(new GenerateClaimResponse.UsedLawArticle(
                        "law-330", "ГК РФ", "330", "понятие неустойки"
                )),
                withoutTrace.attachments(),
                withoutTrace.warnings(),
                withoutTrace.manualReviewRequired()
        );
        GuardrailResult pass = service.check(
                legalRequest,
                traced,
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(block.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(block.errors()).anyMatch(error -> error.contains("without used_law_articles"));
        assertThat(pass.decision()).isEqualTo(GuardrailDecision.PASS);
    }

    @Test
    void actRequiresActNumberDateAndUnilateralCreatorWhenProvided() {
        GenerateClaimRequest request = requestWithActFacts();
        String correct = validText()
                .replace(
                        "Настоящим уведомляем о намерении составить акт о непредоставлении транспортного средства.",
                        "Акт № ACT-77 от 11.06.2026. Настоящий акт в одностороннем порядке составлен ООО Экспедитор."
                );

        GuardrailResult pass = service.check(
                request,
                response(correct, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );
        GuardrailResult block = service.check(
                request,
                response(validText(), GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(pass.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(block.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(block.errors()).anyMatch(error -> error.contains("shipment.act_number"));
        assertThat(block.errors()).anyMatch(error -> error.contains("shipment.act_date"));
    }

    @Test
    void blocksConfirmationSubstitutionAndUnsupportedVehicleDetails() {
        String text = validText()
                .replace("транспортное средство не предоставлено",
                        "факт неподтверждения подачи транспортного средства установлен")
                .replace("Требования к ТС: тент 20 тонн.",
                        "Транспортное средство марки «тент» грузоподъемностью 20 тонн.");

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("absence of confirmation"));
        assertThat(result.errors()).anyMatch(error -> error.contains("vehicle identity detail"));
    }

    @Test
    void blocksInventedCauseAndPositiveArrival() {
        String text = validText()
                .replace("транспортное средство не предоставлено",
                        "транспортное средство прибыло, но водитель покинул место из-за поломки");

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT),
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("provided or arrived"));
        assertThat(result.errors()).anyMatch(error -> error.contains("cause or incident circumstance"));
    }

    @Test
    void notificationRequiresFutureActIntentAndRejectsCompletedAct() {
        String text = validText()
                .replace("уведомляем о намерении составить акт", "сообщаем, что акт уже составлен");

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.NOTIFICATION),
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("prepared in the future"));
        assertThat(result.errors()).anyMatch(error -> error.contains("already completed"));
    }

    @Test
    void notificationRejectsInventedRepresentativeInvitation() {
        String text = validText()
                + " Просим направить представителя для составления совместного акта.";

        GuardrailResult result = service.check(
                request(true),
                response(text, GenerateClaimResponse.DocumentType.NOTIFICATION),
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("attendance instructions"));
    }

    private GenerateClaimRequest requestWithLegalContext() {
        GenerateClaimRequest base = request(true);
        return new GenerateClaimRequest(
                base.caseFacts(),
                base.backendCalculation(),
                base.contractContext(),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "law-330",
                        "ГК РФ",
                        "330",
                        "понятие неустойки",
                        "Неустойкой признается определенная законом или договором денежная сумма",
                        "ст. 330 ГК РФ",
                        "2026-07-30",
                        "LOADING_FAILURE"
                )),
                base.templateContext(),
                base.similarExamples()
        );
    }

    private GenerateClaimRequest requestWithActFacts() {
        GenerateClaimRequest base = request(true);
        GenerateClaimRequest.ShipmentFacts shipment = base.caseFacts().shipment();
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        base.caseFacts().claimId(),
                        base.caseFacts().claimType(),
                        base.caseFacts().creditor(),
                        base.caseFacts().debtor(),
                        base.caseFacts().contract(),
                        new GenerateClaimRequest.ShipmentFacts(
                                shipment.orderNumber(),
                                shipment.route(),
                                "ACT-77",
                                "11.06.2026",
                                shipment.ttnNumber(),
                                shipment.invoiceNumber(),
                                shipment.loadingDate(),
                                shipment.loadingAddress(),
                                shipment.loadingTimeWindow(),
                                shipment.vehicleRequirements(),
                                shipment.carrierName(),
                                shipment.failureConfirmedByDispatcher()
                        ),
                        base.caseFacts().payment(),
                        base.caseFacts().claimDate()
                ),
                base.backendCalculation(),
                base.contractContext(),
                base.legalContext(),
                base.templateContext(),
                base.similarExamples()
        );
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
        String documentText = type == GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
                ? text.replace(
                        "Настоящим уведомляем о намерении составить акт о непредоставлении транспортного средства.",
                        "Настоящий акт в одностороннем порядке составлен ООО Экспедитор."
                )
                : text;
        return new GenerateDocumentResponse(
                type,
                type == GenerateClaimResponse.DocumentType.NOTIFICATION
                        ? "Уведомление о составлении акта"
                        : "Акт о непредоставлении транспортного средства",
                documentText,
                "Факт срыва погрузки зафиксирован",
                List.of(new GenerateClaimResponse.UsedContractClause("5.1", "lf-duty", "обязанность подачи")),
                List.of(),
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
                Требования к ТС: тент 20 тонн.
                Погрузка была назначена на 10.06.2026 по адресу: г. Москва, Складская улица, 1,
                во временной интервал 09:00–11:00, маршрут Москва — Тверь.
                Настоящим уведомляем о намерении составить акт о непредоставлении транспортного средства.
                """;
    }
}
