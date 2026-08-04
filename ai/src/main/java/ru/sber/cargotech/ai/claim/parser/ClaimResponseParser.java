package ru.sber.cargotech.ai.claim.parser;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import tools.jackson.databind.ObjectMapper;

@Service
public class ClaimResponseParser {

    private final ObjectMapper objectMapper;

    public ClaimResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GenerateClaimResponse parse(String rawModelResponse) {
        if (rawModelResponse == null || rawModelResponse.isBlank()) {
            throw new IllegalArgumentException("Raw model response is empty");
        }

        String json = normalizeKnownModelAliases(extractJson(rawModelResponse));

        try {
            GenerateClaimResponse response = objectMapper.readValue(json, GenerateClaimResponse.class);
            validate(response);
            return response;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse GigaChat response as GenerateClaimResponse", e);
        }
    }


    /**
     * GigaChat иногда возвращает смысловые, но не входящие в наш enum значения document_type.
     * Нормализуем только заранее известные безопасные синонимы, чтобы не расширять DTO мусорными типами.
     */
    private String normalizeKnownModelAliases(String json) {
        return json
                .replace("\"document_type\": \"TIR_TRANSPORT_DOCUMENT\"", "\"document_type\": \"TTN\"")
                .replace("\"document_type\":\"TIR_TRANSPORT_DOCUMENT\"", "\"document_type\":\"TTN\"")
                .replace("\"document_type\": \"TRANSPORT_WAYBILL\"", "\"document_type\": \"TTN\"")
                .replace("\"document_type\":\"TRANSPORT_WAYBILL\"", "\"document_type\":\"TTN\"")
                .replace("\"document_type\": \"WAYBILL\"", "\"document_type\": \"TTN\"")
                .replace("\"document_type\":\"WAYBILL\"", "\"document_type\":\"TTN\"");
    }

    private String extractJson(String raw) {
        String cleaned = raw.trim();

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring("```json".length()).trim();
        }

        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length()).trim();
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length()).trim();
        }

        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');

        if (firstBrace < 0 || lastBrace < 0 || lastBrace <= firstBrace) {
            throw new IllegalArgumentException("Model response does not contain JSON object: " + raw);
        }

        return cleaned.substring(firstBrace, lastBrace + 1);
    }

    private void validate(GenerateClaimResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("Parsed response is null");
        }

        if (response.claimType() == null) {
            throw new IllegalArgumentException("claim_type is required in model response");
        }

        if (response.claimText() == null || response.claimText().isBlank()) {
            throw new IllegalArgumentException("claim_text is required in model response");
        }

        if (response.summaryForLawyer() == null || response.summaryForLawyer().isBlank()) {
            throw new IllegalArgumentException("summary_for_lawyer is required in model response");
        }

        if (response.manualReviewRequired() == null || !response.manualReviewRequired()) {
            throw new IllegalArgumentException("manual_review_required must be true");
        }
    }
}