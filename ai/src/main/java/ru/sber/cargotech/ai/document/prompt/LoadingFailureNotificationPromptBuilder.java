package ru.sber.cargotech.ai.document.prompt;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class LoadingFailureNotificationPromptBuilder {

    private final ObjectMapper objectMapper;

    public LoadingFailureNotificationPromptBuilder(ObjectMapper objectMapper) {
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
                Ты — AI-модуль для подготовки юридически нейтральных документов в логистических спорах.

                Твоя задача — подготовить уведомление о составлении акта о непредоставлении транспортного средства.

                Строгие правила:
                1. Используй только факты, переданные во входном JSON.
                2. Не выдумывай даты, адреса, время, реквизиты, водителя, госномер, документы, пункты договора и статьи закона.
                3. Документ не является претензией.
                4. Не требуй оплату, штраф, убытки или неустойку.
                5. Не пересчитывай суммы и не используй backend_calculation для требований об оплате.
                6. Не пиши про суд, иск, судебную эскалацию или взыскание через суд.
                7. Смысл уведомления — сообщить контрагенту о факте непредоставления ТС и о составлении акта.
                8. Предложи контрагенту присутствовать при составлении акта или направить представителя, только если это не противоречит входным данным.
                9. contract_context используй только как источник условий договора.
                10. Ссылайся только на те пункты договора, которые присутствуют в contract_context.
                11. Не добавляй статьи закона, если они отсутствуют во входном legal_context.
                12. Если данных недостаточно, не додумывай их и добавь предупреждение в warnings.
                13. Тон документа — официальный, сухой, деловой.
                14. Верни только валидный JSON без markdown и без текста вне JSON.
                15. manual_review_required всегда true.
                """;
    }

    private String buildUserPrompt(GenerateClaimRequest request) {
        String inputJson = toJson(request);

        return """
                Сформируй уведомление о составлении акта о непредоставлении транспортного средства.

                Входные данные:
                {INPUT_JSON}

                Верни ответ строго в таком JSON-формате:

                {
                  "document_type": "NOTIFICATION",
                  "document_title": "Уведомление о составлении акта о непредоставлении транспортного средства",
                  "document_text": "Полный текст уведомления",
                  "summary_for_lawyer": "Краткое резюме для юриста",
                  "used_contract_clauses": [
                    {
                      "clause_number": "номер пункта договора",
                      "chunk_id": "id использованного фрагмента",
                      "reason": "почему этот пункт использован"
                    }
                  ],
                  "attachments": [
                    {
                      "document_type": "TRANSPORT_ORDER",
                      "document_name": "название документа",
                      "required": true
                    }
                  ],
                  "warnings": [],
                  "manual_review_required": true
                }

                Требования к document_text:
                1. Укажи отправителя только из case_facts.creditor.
                2. Укажи получателя только из case_facts.debtor.
                3. Укажи договор только из case_facts.contract.
                4. Укажи заявку, маршрут, дату погрузки, адрес погрузки, временное окно и требования к ТС только если они есть в case_facts.shipment.
                5. Укажи, что транспортное средство не было предоставлено к погрузке, только если failure_confirmed_by_dispatcher = true.
                6. Укажи, что отправитель составляет акт о непредоставлении ТС.
                7. Не требуй оплату штрафа.
                8. Не включай расчёт штрафа.
                9. Не называй документ претензией.
                10. Не упоминай суд.
                11. Если отсутствуют дата, адрес или временное окно погрузки, добавь предупреждение в warnings.

                Требования к used_contract_clauses:
                1. Каждый clause_number должен существовать в contract_context.
                2. Каждый chunk_id должен существовать в contract_context.
                3. Не добавляй пункты договора самостоятельно.

                Требования к attachments:
                1. Не выдумывай документы.
                2. Если заявка указана во входных данных, можно добавить TRANSPORT_ORDER.
                3. Если акт ещё только составляется, не добавляй LOADING_FAILURE_ACT как уже существующее приложение.
                """.replace("{INPUT_JSON}", inputJson);
    }

    private String toJson(GenerateClaimRequest request) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize request for notification prompt", e);
        }
    }

    private void validate(GenerateClaimRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("GenerateClaimRequest is null");
        }

        if (request.caseFacts() == null) {
            throw new IllegalArgumentException("case_facts is required");
        }

        if (request.caseFacts().claimType() != GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            throw new IllegalArgumentException("Only LOADING_FAILURE is supported by LoadingFailureNotificationPromptBuilder");
        }

        if (request.caseFacts().shipment() == null) {
            throw new IllegalArgumentException("case_facts.shipment is required");
        }
    }
}