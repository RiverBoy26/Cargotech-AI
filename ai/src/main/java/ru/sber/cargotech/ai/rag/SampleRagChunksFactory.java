package ru.sber.cargotech.ai.rag;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SampleRagChunksFactory {

    public List<RagChunk> paymentDelayChunks() {
        return List.of(
                contractPaymentTerm(),
                contractPenaltyTerm(),
                contractPretrialOrder(),

                legal309(),
                legal310(),
                legal314(),
                legal330(),

                paymentDelayTemplate(),
                similarPaymentDelayExample()
        );
    }

    private RagChunk contractPaymentTerm() {
        return new RagChunk(
                "chunk_contract_001",
                RagCollection.CONTRACT_CONTEXT,
                RagChunkType.PAYMENT_TERM,
                "PAYMENT_DELAY",
                "demo_client",
                "contract_45_2026",
                "45/2026",
                "10.01.2026",
                "exp_client",
                "ТЭУ",
                "contract_45_2026",
                "Договор №45/2026 от 10.01.2026",
                "Порядок оплаты",
                "4.2",
                "Заказчик обязан оплатить оказанные услуги в течение 30 календарных дней с даты подписания акта оказанных услуг.",
                "п. 4.2 Договора №45/2026 от 10.01.2026",
                true,
                Map.of()
        );
    }

    private RagChunk contractPenaltyTerm() {
        return new RagChunk(
                "chunk_contract_002",
                RagCollection.CONTRACT_CONTEXT,
                RagChunkType.CONTRACT_PENALTY,
                "PAYMENT_DELAY",
                "demo_client",
                "contract_45_2026",
                "45/2026",
                "10.01.2026",
                "exp_client",
                "ТЭУ",
                "contract_45_2026",
                "Договор №45/2026 от 10.01.2026",
                "Ответственность сторон",
                "6.1",
                "В случае нарушения срока оплаты Заказчик уплачивает Исполнителю неустойку в размере 0,1% от суммы просроченного платежа за каждый календарный день просрочки.",
                "п. 6.1 Договора №45/2026 от 10.01.2026",
                true,
                Map.of()
        );
    }

    private RagChunk contractPretrialOrder() {
        return new RagChunk(
                "chunk_contract_003",
                RagCollection.CONTRACT_CONTEXT,
                RagChunkType.PRETRIAL_ORDER,
                "PAYMENT_DELAY",
                "demo_client",
                "contract_45_2026",
                "45/2026",
                "10.01.2026",
                "exp_client",
                "ТЭУ",
                "contract_45_2026",
                "Договор №45/2026 от 10.01.2026",
                "Претензионный порядок",
                "8.1",
                "Сторона, получившая претензию, обязана направить мотивированный ответ в течение 15 календарных дней с даты её получения.",
                "п. 8.1 Договора №45/2026 от 10.01.2026",
                true,
                Map.of()
        );
    }

    private RagChunk legal309() {
        return legal(
                "chunk_legal_gk_309",
                "309",
                "надлежащее исполнение обязательств",
                "Обязательства должны исполняться надлежащим образом в соответствии с условиями обязательства и требованиями закона."
        );
    }

    private RagChunk legal310() {
        return legal(
                "chunk_legal_gk_310",
                "310",
                "запрет одностороннего отказа от исполнения обязательства",
                "Односторонний отказ от исполнения обязательства и одностороннее изменение его условий не допускаются, если иное не предусмотрено законом."
        );
    }

    private RagChunk legal314() {
        return legal(
                "chunk_legal_gk_314",
                "314",
                "исполнение обязательства в установленный срок",
                "Если обязательство предусматривает день исполнения или период исполнения, обязательство подлежит исполнению в соответствующий день или период."
        );
    }

    private RagChunk legal330() {
        return legal(
                "chunk_legal_gk_330",
                "330",
                "договорная неустойка",
                "Неустойкой признаётся определённая законом или договором денежная сумма, которую должник обязан уплатить кредитору при неисполнении или ненадлежащем исполнении обязательства."
        );
    }

    private RagChunk legal(String chunkId, String article, String purpose, String text) {
        return new RagChunk(
                chunkId,
                RagCollection.LEGAL_CONTEXT,
                RagChunkType.LEGAL_ARTICLE,
                "PAYMENT_DELAY",
                null,
                null,
                null,
                null,
                null,
                "ТЭУ",
                "legal_norms",
                "ГК РФ ст. " + article,
                null,
                null,
                text,
                "ст. " + article + " ГК РФ",
                true,
                Map.of(
                        "law_code", "ГК РФ",
                        "article", article,
                        "purpose", purpose,
                        "topic", purpose
                )
        );
    }

    private RagChunk paymentDelayTemplate() {
        return new RagChunk(
                "chunk_template_payment_delay_default",
                RagCollection.TEMPLATE_CONTEXT,
                RagChunkType.CLAIM_TEMPLATE,
                "PAYMENT_DELAY",
                null,
                null,
                null,
                null,
                null,
                null,
                "template_payment_delay_default",
                "Претензия о просрочке оплаты",
                null,
                null,
                "Структура претензии: реквизиты сторон, ссылка на договор, описание оказанной услуги, нарушение срока оплаты, расчёт задолженности и неустойки, правовое основание, требование об оплате, приложения.",
                "Шаблон претензии о просрочке оплаты",
                true,
                Map.of(
                        "template_id", "template_payment_delay_default",
                        "template_name", "Претензия о просрочке оплаты",
                        "template_type", "PAYMENT_DELAY",
                        "template_structure", List.of(
                                "Реквизиты сторон",
                                "Ссылка на договор",
                                "Описание оказанной услуги",
                                "Описание нарушения срока оплаты",
                                "Расчёт задолженности и неустойки",
                                "Правовое основание",
                                "Требование об оплате",
                                "Приложения"
                        )
                )
        );
    }

    private RagChunk similarPaymentDelayExample() {
        return new RagChunk(
                "chunk_example_payment_delay_012",
                RagCollection.SIMILAR_EXAMPLE,
                RagChunkType.STYLE_EXAMPLE,
                "PAYMENT_DELAY",
                null,
                null,
                null,
                null,
                null,
                null,
                "example_012",
                "Похожая претензия по просрочке оплаты",
                null,
                null,
                "В похожей претензии юрист сначала указал договор, затем акт, сумму долга, срок оплаты, размер неустойки и итоговое требование.",
                "example_012",
                true,
                Map.of(
                        "example_id", "example_012",
                        "usage_rule", "Использовать только как пример структуры и стиля. Не копировать факты, суммы, даты и реквизиты.",
                        "structure_summary", "В похожей претензии юрист сначала указал договор, затем акт, сумму долга, срок оплаты, размер неустойки и итоговое требование."
                )
        );
    }
}