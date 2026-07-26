package ru.sber.cargotech.ai.rag;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.ai.rag.dto.IndexRagChunksRequest;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class RagIndexRequestMapper {

    private static final int MAX_BATCH_SIZE = 200;

    private static final Pattern PASSPORT = Pattern.compile("\\b\\d{4}\\s?\\d{6}\\b");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("\\b\\d{20}\\b");
    private static final Pattern CARD = Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+7|8)[\\s(-]?\\d{3}[\\s)-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");

    public List<RagChunk> toChunks(IndexRagChunksRequest request) {
        List<String> errors = new ArrayList<>();

        if (request == null) {
            throw new IllegalArgumentException("Index request is null");
        }

        validateNoBrokenEncoding(request.sourceBatchId(), "source_batch_id", errors);
        validateNoBrokenEncoding(request.sourceSystem(), "source_system", errors);

        if (request.chunks() == null || request.chunks().isEmpty()) {
            throw new IllegalArgumentException("chunks are required");
        }

        if (request.chunks().size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Too many chunks in one batch. Max=" + MAX_BATCH_SIZE);
        }

        Set<String> chunkIds = new HashSet<>();
        List<RagChunk> result = new ArrayList<>();

        for (int i = 0; i < request.chunks().size(); i++) {
            IndexRagChunksRequest.IndexRagChunk input = request.chunks().get(i);
            String prefix = "chunks[" + i + "]";

            validateChunk(input, prefix, errors);

            if (input != null && hasText(input.chunkId())) {
                if (!chunkIds.add(input.chunkId())) {
                    errors.add(prefix + ".chunk_id duplicated in batch: " + input.chunkId());
                }
            }

            if (input != null) {
                validateNoRawPii(input.text(), prefix, errors);
                validateNoBrokenEncoding(input.chunkId(), prefix + ".chunk_id", errors);
                validateNoBrokenEncoding(input.claimType(), prefix + ".claim_type", errors);
                validateNoBrokenEncoding(input.clientId(), prefix + ".client_id", errors);
                validateNoBrokenEncoding(input.contractId(), prefix + ".contract_id", errors);
                validateNoBrokenEncoding(input.contractNumber(), prefix + ".contract_number", errors);
                validateNoBrokenEncoding(input.contractDate(), prefix + ".contract_date", errors);
                validateNoBrokenEncoding(input.contour(), prefix + ".contour", errors);
                validateNoBrokenEncoding(input.contractType(), prefix + ".contract_type", errors);
                validateNoBrokenEncoding(input.sourceId(), prefix + ".source_id", errors);
                validateNoBrokenEncoding(input.sourceTitle(), prefix + ".source_title", errors);
                validateNoBrokenEncoding(input.sectionTitle(), prefix + ".section_title", errors);
                validateNoBrokenEncoding(input.sectionPath(), prefix + ".section_path", errors);
                validateNoBrokenEncoding(input.clauseNumber(), prefix + ".clause_number", errors);
                validateNoBrokenEncoding(input.clauseTopic(), prefix + ".clause_topic", errors);
                validateNoBrokenEncoding(input.text(), prefix + ".text", errors);
                validateNoBrokenEncoding(input.citation(), prefix + ".citation", errors);
                validateNoBrokenEncodingInObject(input.extra(), prefix + ".extra", errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid RAG index request: " + String.join("; ", errors));
        }

        for (IndexRagChunksRequest.IndexRagChunk input : request.chunks()) {
            result.add(toChunk(request, input));
        }

        return result;
    }

    private void validateChunk(
            IndexRagChunksRequest.IndexRagChunk chunk,
            String prefix,
            List<String> errors
    ) {
        if (chunk == null) {
            errors.add(prefix + " is null");
            return;
        }

        if (!hasText(chunk.chunkId())) {
            errors.add(prefix + ".chunk_id is required");
        }

        if (chunk.ragCollection() == null) {
            errors.add(prefix + ".rag_collection is required");
        }

        if (chunk.chunkType() == null) {
            errors.add(prefix + ".chunk_type is required");
        }

        if (!hasText(chunk.text())) {
            errors.add(prefix + ".text is required");
        }

        if (!hasText(chunk.citation())) {
            errors.add(prefix + ".citation is required");
        }

        if (chunk.ragCollection() == RagCollection.CONTRACT_CONTEXT) {
            require(chunk.contractId(), prefix + ".contract_id", errors);
            require(chunk.contractNumber(), prefix + ".contract_number", errors);
            require(chunk.contractDate(), prefix + ".contract_date", errors);
            require(chunk.sectionTitle(), prefix + ".section_title", errors);
            require(chunk.sectionPath(), prefix + ".section_path", errors);
            require(chunk.clauseNumber(), prefix + ".clause_number", errors);
            require(chunk.clauseTopic(), prefix + ".clause_topic", errors);
        }

        if (chunk.ragCollection() == RagCollection.LEGAL_CONTEXT) {
            requireExtra(chunk.extra(), "law_code", prefix, errors);
            requireExtra(chunk.extra(), "article", prefix, errors);
            requireExtra(chunk.extra(), "purpose", prefix, errors);
        }

        if (chunk.ragCollection() == RagCollection.TEMPLATE_CONTEXT) {
            requireExtra(chunk.extra(), "template_id", prefix, errors);
            requireExtra(chunk.extra(), "template_name", prefix, errors);
            requireExtra(chunk.extra(), "template_structure", prefix, errors);
        }

        if (chunk.ragCollection() == RagCollection.SIMILAR_EXAMPLE) {
            requireExtra(chunk.extra(), "example_id", prefix, errors);
            requireExtra(chunk.extra(), "usage_rule", prefix, errors);
            requireExtra(chunk.extra(), "structure_summary", prefix, errors);
        }
    }

    private RagChunk toChunk(
            IndexRagChunksRequest request,
            IndexRagChunksRequest.IndexRagChunk input
    ) {
        Map<String, Object> extra = new LinkedHashMap<>();

        if (input.extra() != null) {
            extra.putAll(input.extra());
        }

        put(extra, "source_batch_id", request.sourceBatchId());
        put(extra, "source_system", request.sourceSystem());
        put(extra, "section_path", input.sectionPath());
        put(extra, "clause_topic", input.clauseTopic());

        return new RagChunk(
                input.chunkId(),
                input.ragCollection(),
                input.chunkType(),

                input.claimType(),
                input.clientId(),

                input.contractId(),
                input.contractNumber(),
                input.contractDate(),
                input.contour(),
                input.contractType(),

                input.sourceId(),
                input.sourceTitle(),

                input.sectionTitle(),
                input.clauseNumber(),

                input.text(),
                input.citation(),

                input.isCurrent() == null ? true : input.isCurrent(),

                extra
        );
    }

    private void validateNoRawPii(String text, String prefix, List<String> errors) {
        if (text == null || text.isBlank()) {
            return;
        }

        if (PASSPORT.matcher(text).find()) {
            errors.add(prefix + ".text contains possible raw passport data. Mask PII before embeddings.");
        }

        if (PHONE.matcher(text).find()) {
            errors.add(prefix + ".text contains possible raw phone. Mask PII before embeddings.");
        }

        if (EMAIL.matcher(text).find()) {
            errors.add(prefix + ".text contains possible raw email. Mask PII before embeddings.");
        }

        if (CARD.matcher(text).find()) {
            errors.add(prefix + ".text contains possible raw bank card. Mask PII before embeddings.");
        }

        if (BANK_ACCOUNT.matcher(text).find()) {
            errors.add(prefix + ".text contains possible raw bank account. Mask PII before embeddings.");
        }
    }

    private void validateNoBrokenEncodingInObject(Object value, String field, List<String> errors) {
        if (value == null) {
            return;
        }

        if (value instanceof String stringValue) {
            validateNoBrokenEncoding(stringValue, field, errors);
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateNoBrokenEncodingInObject(entry.getValue(), field + "." + entry.getKey(), errors);
            }
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object item : iterable) {
                validateNoBrokenEncodingInObject(item, field + "[" + i + "]", errors);
                i++;
            }
        }
    }

    private void validateNoBrokenEncoding(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) {
            return;
        }

        if (value.contains("Ð") || value.contains("Ñ") || value.contains("�")) {
            errors.add(field + " looks like broken UTF-8/mojibake. Re-save or resend text as UTF-8 before indexing.");
        }

        long questionMarks = value.chars().filter(ch -> ch == '?').count();

        if (questionMarks >= 3) {
            errors.add(field + " contains too many question marks. Text is probably corrupted before indexing.");
        }
    }

    private void require(String value, String field, List<String> errors) {
        if (!hasText(value)) {
            errors.add(field + " is required");
        }
    }

    private void requireExtra(Map<String, Object> extra, String key, String prefix, List<String> errors) {
        if (extra == null || extra.get(key) == null || String.valueOf(extra.get(key)).isBlank()) {
            errors.add(prefix + ".extra." + key + " is required");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
