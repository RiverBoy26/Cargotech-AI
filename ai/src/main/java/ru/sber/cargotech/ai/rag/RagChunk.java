package ru.sber.cargotech.ai.rag;

import java.util.*;

public record RagChunk(
        String chunkId,
        RagCollection ragCollection,
        RagChunkType chunkType,

        String claimType,
        String clientId,

        String contractId,
        String contractNumber,
        String contractDate,
        String contour,
        String contractType,

        String sourceId,
        String sourceTitle,

        String sectionTitle,
        String clauseNumber,

        String text,
        String citation,

        Boolean isCurrent,

        Map<String, Object> extra
) {
    public String embeddingText() {
        StringBuilder builder = new StringBuilder();

        append(builder, "Логическая коллекция", ragCollection == null ? null : ragCollection.name());
        append(builder, "Тип чанка", chunkType == null ? null : chunkType.name());
        append(builder, "Тип претензии", claimType);
        append(builder, "Источник", sourceTitle);
        append(builder, "Раздел", sectionTitle);
        append(builder, "Пункт", clauseNumber);
        append(builder, "Цитирование", citation);

        builder.append("Текст:\n").append(text == null ? "" : text);

        return builder.toString();
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();

        put(payload, "chunk_id", chunkId);
        put(payload, "rag_collection", ragCollection == null ? null : ragCollection.name());
        put(payload, "chunk_type", chunkType == null ? null : chunkType.name());

        put(payload, "claim_type", claimType);
        put(payload, "client_id", clientId);

        put(payload, "contract_id", contractId);
        put(payload, "contract_number", contractNumber);
        put(payload, "contract_date", contractDate);
        put(payload, "contour", contour);
        put(payload, "contract_type", contractType);

        put(payload, "source_id", sourceId);
        put(payload, "source_title", sourceTitle);

        put(payload, "section_title", sectionTitle);
        put(payload, "clause_number", clauseNumber);

        put(payload, "text", text);
        put(payload, "citation", citation);

        put(payload, "is_current", isCurrent);

        if (extra != null) {
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                put(payload, entry.getKey(), entry.getValue());
            }
        }

        return payload;
    }

    public static RagChunk fromPayload(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }

        Set<String> known = Set.of(
                "chunk_id", "rag_collection", "chunk_type",
                "claim_type", "client_id",
                "contract_id", "contract_number", "contract_date", "contour", "contract_type",
                "source_id", "source_title",
                "section_title", "clause_number",
                "text", "citation", "is_current"
        );

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!known.contains(entry.getKey())) {
                extra.put(entry.getKey(), entry.getValue());
            }
        }

        return new RagChunk(
                str(payload.get("chunk_id")),
                enumValue(RagCollection.class, str(payload.get("rag_collection"))),
                enumValue(RagChunkType.class, str(payload.get("chunk_type"))),

                str(payload.get("claim_type")),
                str(payload.get("client_id")),

                str(payload.get("contract_id")),
                str(payload.get("contract_number")),
                str(payload.get("contract_date")),
                str(payload.get("contour")),
                str(payload.get("contract_type")),

                str(payload.get("source_id")),
                str(payload.get("source_title")),

                str(payload.get("section_title")),
                str(payload.get("clause_number")),

                str(payload.get("text")),
                str(payload.get("citation")),

                bool(payload.get("is_current")),

                extra
        );
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append(": ").append(value).append("\n");
        }
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Boolean b) {
            return b;
        }

        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static <T extends Enum<T>> T enumValue(Class<T> enumClass, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return Enum.valueOf(enumClass, value);
    }
}