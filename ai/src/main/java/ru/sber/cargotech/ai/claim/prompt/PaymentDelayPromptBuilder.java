package ru.sber.cargotech.ai.claim.prompt;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class PaymentDelayPromptBuilder {

    private final ObjectMapper objectMapper;

    public PaymentDelayPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<GigaChatMessage> build(GenerateClaimRequest request) {
        validate(request);

        return List.of(
                new GigaChatMessage("system", buildSystemPrompt()),
                new GigaChatMessage("user", buildUserPrompt(request))
        );
    }

    private String buildSystemPrompt() {
        return """
                Ты — AI-модуль для подготовки черновиков претензий по логистическим спорам.

                Твоя задача — подготовить черновик претензии по просрочке оплаты услуг.

                Строгие правила:
                1. Используй только факты, переданные во входном JSON.
                2. Не выдумывай даты, суммы, реквизиты, документы, пункты договора и статьи закона.
                3. Не пересчитывай суммы и неустойку. Используй только backend_calculation.
                4. Не меняй сумму основного долга, сумму неустойки, количество дней просрочки и итоговую сумму.
                5. contract_context используй только как источник условий договора.
                6. Ссылайся только на те пункты договора, которые присутствуют в contract_context.
                7. Не выдумывай номера пунктов договора.
                8. legal_context используй только как источник правовых оснований.
                9. Ссылайся только на те статьи закона, которые присутствуют в legal_context.
                10. Не добавляй статьи закона самостоятельно.
                11. template_context используй как источник структуры документа.
                12. similar_examples используй только как пример структуры и стиля.
                13. Никогда не копируй из similar_examples факты, суммы, даты, реквизиты и персональные данные.
                14. Если данных недостаточно или они противоречат друг другу, не додумывай недостающие сведения и добавь предупреждение в warnings.
                15. Не пиши про суд, иск, судебную эскалацию или дальнейшее взыскание через суд.
                16. Тон документа — официальный, сухой, юридически нейтральный.
                17. Верни только валидный JSON без markdown, без пояснений и без текста вне JSON.
                18. Поле manual_review_required всегда устанавливай в true.
                """;
    }

    private String buildUserPrompt(GenerateClaimRequest request) {
        String inputJson = toJson(request);

        return """
                Сформируй черновик претензии по типу PAYMENT_DELAY.

                Входные данные:
                {INPUT_JSON}

                Верни ответ строго в таком JSON-формате:

                {
                  "claim_type": "PAYMENT_DELAY",
                  "claim_text": "Полный текст претензии",
                  "summary_for_lawyer": "Краткое резюме для юриста",
                  "used_contract_clauses": [
                    {
                      "clause_number": "номер пункта договора",
                      "chunk_id": "id использованного фрагмента",
                      "reason": "почему этот пункт использован"
                    }
                  ],
                  "used_law_articles": [
                    {
                      "law_code": "кодекс или закон",
                      "article": "номер статьи",
                      "reason": "почему эта статья использована"
                    }
                  ],
                  "backend_calculation_used": {
                    "principal_debt": 0,
                    "penalty_type": "CONTRACT_PENALTY",
                    "penalty_amount": 0,
                    "total_amount": 0,
                    "overdue_days": 0,
                    "currency": "RUB"
                  },
                  "attachments": [
                    {
                      "document_type": "CONTRACT",
                      "document_name": "название документа",
                      "required": true
                    }
                  ],
                  "warnings": [],
                  "manual_review_required": true
                }

                Требования к claim_text:
                1. Укажи получателя претензии только на основании case_facts.debtor.
                2. Укажи отправителя претензии только на основании case_facts.creditor.
                3. Укажи название документа: претензия о нарушении срока оплаты оказанных услуг.
                4. Сошлись на договоре только по данным case_facts.contract.
                5. Опиши оказанную услугу или перевозку только по данным case_facts.shipment.
                6. Укажи подтверждающие документы только если они присутствуют во входных данных.
                7. Укажи срок оплаты только по данным case_facts.payment и подтверждённому contract_context.
                8. Укажи факт отсутствия оплаты только если payment_status это подтверждает.
                9. Укажи сумму основного долга строго из backend_calculation.principal_debt.
                10. Укажи неустойку строго из backend_calculation, если она предусмотрена входными данными.
                11. Укажи итоговую сумму требования строго из backend_calculation.total_amount.
                12. Используй только пункты договора из contract_context.
                13. Используй только статьи закона из legal_context.
                14. Сформулируй требование об оплате без добавления новых сроков, если срок требования не задан входными данными.
                15. Сформируй список приложений только из документов, наличие которых подтверждается входными данными.
                16. Не добавляй отсутствующие факты для улучшения текста.
                17. Если обязательный для формулировки факт отсутствует, отрази это в warnings.

                Требования к used_contract_clauses:
                1. Каждый clause_number должен существовать в contract_context.
                2. Каждый chunk_id должен существовать в contract_context.
                3. Не включай неиспользованные пункты договора.

                Требования к used_law_articles:
                1. Каждая статья должна существовать в legal_context.
                2. Не добавляй статьи самостоятельно.
                3. Не включай неиспользованные статьи.

                Требования к backend_calculation_used:
                1. Скопируй значения из backend_calculation без пересчёта.
                2. Не округляй и не изменяй значения.
                3. Не исправляй формулу самостоятельно.

                Требования к attachments:
                1. Не выдумывай документы.
                2. Добавляй только документы, существование которых подтверждается входными данными.
                3. Если документ упомянут в claim_text как приложение, он должен присутствовать в attachments.
                """.replace("{INPUT_JSON}", inputJson);
    }

    private String toJson(GenerateClaimRequest request) {
        try {
            return objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize GenerateClaimRequest for prompt",
                    e
            );
        }
    }

    private void validate(GenerateClaimRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("GenerateClaimRequest is null");
        }

        if (request.caseFacts() == null) {
            throw new IllegalArgumentException("case_facts is required");
        }

        if (request.caseFacts().claimType() == null) {
            throw new IllegalArgumentException("case_facts.claim_type is required");
        }

        if (request.caseFacts().claimType()
                != GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            throw new IllegalArgumentException(
                    "Only PAYMENT_DELAY is supported on this step"
            );
        }

        if (request.backendCalculation() == null) {
            throw new IllegalArgumentException(
                    "backend_calculation is required"
            );
        }
    }
}