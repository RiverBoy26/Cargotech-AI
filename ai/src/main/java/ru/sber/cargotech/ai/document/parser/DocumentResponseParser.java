package ru.sber.cargotech.ai.document.parser;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentResponseParser {

    private final ObjectMapper objectMapper;

    public DocumentResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GenerateDocumentResponse parse(String rawModelResponse) {
        if (rawModelResponse == null || rawModelResponse.isBlank()) {
            throw new IllegalArgumentException("Raw model response is empty");
        }

        String json = extractJson(rawModelResponse);
        try {
            GenerateDocumentResponse response = objectMapper.readValue(json, GenerateDocumentResponse.class);
            validate(response);
            return response;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse GigaChat response as GenerateDocumentResponse", e);
        }
    }

    private String extractJson(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7).trim();
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3).trim();
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();

        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw new IllegalArgumentException("Model response does not contain one JSON object");
        }
        return cleaned.substring(firstBrace, lastBrace + 1);
    }

    private void validate(GenerateDocumentResponse response) {
        if (response == null) throw new IllegalArgumentException("Parsed response is null");
        if (response.documentType() == null) throw new IllegalArgumentException("document_type is required");
        if (response.documentTitle() == null || response.documentTitle().isBlank()) {
            throw new IllegalArgumentException("document_title is required");
        }
        if (response.documentText() == null || response.documentText().isBlank()) {
            throw new IllegalArgumentException("document_text is required");
        }
        if (response.summaryForLawyer() == null || response.summaryForLawyer().isBlank()) {
            throw new IllegalArgumentException("summary_for_lawyer is required");
        }
        if (!Boolean.TRUE.equals(response.manualReviewRequired())) {
            throw new IllegalArgumentException("manual_review_required must be true");
        }
    }
}
