package ru.sber.cargotech.ai.claim.api;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.claim.guardrail.RuleBasedGuardrailService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/claims")
public class ClaimGuardrailTestController {

    private final RuleBasedGuardrailService guardrailService;

    public ClaimGuardrailTestController(RuleBasedGuardrailService guardrailService) {
        this.guardrailService = guardrailService;
    }

    @PostMapping("/guardrails/test")
    public Map<String, Object> testGuardrails(@RequestBody GuardrailTestPayload payload) {
        GuardrailResult result = guardrailService.check(payload.request(), payload.response());

        return Map.of(
                "success", true,
                "result", result,
                "checkedAt", Instant.now().toString()
        );
    }

    @GetMapping("/guardrails/smoke")
    public Map<String, Object> smokeTest() {
        GuardrailResult result = guardrailService.check(sampleRequest(), sampleResponse());

        return Map.of(
                "success", true,
                "result", result,
                "checkedAt", Instant.now().toString()
        );
    }

    public record GuardrailTestPayload(
            GenerateClaimRequest request,
            GenerateClaimResponse response
    ) {
    }

    private GenerateClaimRequest sampleRequest() {
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "claim_001",
                        GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        new GenerateClaimRequest.Party(
                                "ООО \"Экспедитор\"",
                                "7800000000",
                                "г. Санкт-Петербург, ул. Примерная, д. 1"
                        ),
                        new GenerateClaimRequest.Party(
                                "ООО \"Клиент\"",
                                "7700000000",
                                "г. Москва, ул. Тестовая, д. 10"
                        ),
                        new GenerateClaimRequest.ContractFacts(
                                "45/2026",
                                "10.01.2026"
                        ),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-157",
                                "Санкт-Петербург — Москва",
                                "157",
                                "01.05.2026",
                                "ТТН-157",
                                "INV-157"
                        ),
                        new GenerateClaimRequest.PaymentFacts(
                                "31.05.2026",
                                GenerateClaimRequest.PaymentStatus.UNPAID,
                                true
                        ),
                        "10.06.2026"
                ),
                new GenerateClaimRequest.BackendCalculation(
                        new BigDecimal("240000.00"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        "0,1% от суммы просроченного платежа за каждый календарный день просрочки",
                        10,
                        new BigDecimal("2400.00"),
                        new BigDecimal("242400.00"),
                        "RUB",
                        "240000 × 0,1% × 10 = 2400"
                ),
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "chunk_contract_001",
                                "4.2",
                                "Порядок оплаты",
                                "Заказчик обязан оплатить оказанные услуги в течение 30 календарных дней с даты подписания акта оказанных услуг."
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "chunk_contract_002",
                                "6.1",
                                "Ответственность сторон",
                                "В случае нарушения срока оплаты Заказчик уплачивает Исполнителю неустойку в размере 0,1% от суммы просроченного платежа за каждый календарный день просрочки."
                        )
                ),
                List.of(
                        new GenerateClaimRequest.LegalContextItem("ГК РФ", "309", "надлежащее исполнение обязательств"),
                        new GenerateClaimRequest.LegalContextItem("ГК РФ", "310", "запрет одностороннего отказа от исполнения обязательства"),
                        new GenerateClaimRequest.LegalContextItem("ГК РФ", "314", "исполнение обязательства в установленный срок"),
                        new GenerateClaimRequest.LegalContextItem("ГК РФ", "330", "договорная неустойка")
                ),
                new GenerateClaimRequest.TemplateContext(
                        "template_payment_delay_default",
                        "Претензия о просрочке оплаты",
                        GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        List.of(
                                "Реквизиты сторон",
                                "Ссылка на договор",
                                "Описание оказанной услуги",
                                "Описание нарушения срока оплаты",
                                "Расчёт задолженности и неустойки",
                                "Правовое основание",
                                "Требование об оплате",
                                "Приложения"
                        )
                ),
                List.of()
        );
    }

    private GenerateClaimResponse sampleResponse() {
        return new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "Претензия о нарушении срока оплаты оказанных услуг. Сумма основного долга составляет 240 000,00 руб. Неустойка составляет 2 400,00 руб. Итоговая сумма требования составляет 242 400,00 руб.",
                "Подготовлен черновик претензии по просрочке оплаты.",
                List.of(
                        new GenerateClaimResponse.UsedContractClause(
                                "4.2",
                                "chunk_contract_001",
                                "Определяет срок оплаты оказанных услуг."
                        ),
                        new GenerateClaimResponse.UsedContractClause(
                                "6.1",
                                "chunk_contract_002",
                                "Определяет размер договорной неустойки."
                        )
                ),
                List.of(
                        new GenerateClaimResponse.UsedLawArticle(
                                "ГК РФ",
                                "309",
                                "Обязанность надлежащего исполнения обязательства."
                        ),
                        new GenerateClaimResponse.UsedLawArticle(
                                "ГК РФ",
                                "330",
                                "Договорная неустойка."
                        )
                ),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("240000.00"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        new BigDecimal("2400.00"),
                        new BigDecimal("242400.00"),
                        10,
                        "RUB"
                ),
                List.of(
                        new GenerateClaimResponse.Attachment(
                                GenerateClaimResponse.DocumentType.CONTRACT,
                                "Договор №45/2026 от 10.01.2026",
                                true
                        ),
                        new GenerateClaimResponse.Attachment(
                                GenerateClaimResponse.DocumentType.ACT,
                                "Акт №157 от 01.05.2026",
                                true
                        )
                ),
                List.of(),
                true
        );
    }
}