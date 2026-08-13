package ru.sber.cargotech.ai.security;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reversible masking for data sent to the external LLM provider.
 *
 * Unlike {@link SensitiveDataMasker}, this service intentionally does not mask
 * monetary amounts, dates, contract numbers or legal citations because those
 * values are required for fact-consistent document generation. It masks
 * personal/contact/banking identifiers and party/address values, keeps a
 * request-local reverse map, and restores only placeholders created for that
 * exact request.
 */
@Service
public class ReversiblePromptMasker {

    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+7|8)[\\s(-]?\\d{3}[\\s)-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}(?!\\d)");
    private static final Pattern PASSPORT = Pattern.compile("(?<!\\d)\\d{4}\\s?\\d{6}(?!\\d)");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("(?<!\\d)\\d{20}(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)");
    private static final Pattern INN = Pattern.compile("(?<!\\d)(?:\\d{10}|\\d{12})(?!\\d)");
    private static final Pattern OGRN = Pattern.compile("(?<!\\d)(?:\\d{13}|\\d{15})(?!\\d)");
    private static final Pattern VEHICLE_NUMBER = Pattern.compile(
            "(?iu)(?<![А-ЯA-Z0-9])[АВЕКМНОРСТУХABEKMHOPCTYX]\\s?\\d{3}\\s?[АВЕКМНОРСТУХABEKMHOPCTYX]{2}\\s?\\d{2,3}(?![А-ЯA-Z0-9])"
    );
    private static final Pattern PLACEHOLDER = Pattern.compile("__CT_PII_[A-Z_]+_\\d{3}__");

    private static final List<String> STRUCTURED_FIELDS = List.of(
            "name",
            "inn",
            "legal_address",
            "bank_details",
            "loading_address",
            "carrier_name",
            "driver_name",
            "driver_phone",
            "phone",
            "email",
            "passport",
            "bank_account",
            "correspondent_account",
            "bik",
            "kpp",
            "ogrn",
            "vehicle_number",
            "vehicle_plate"
    );

    public MaskedPrompt mask(List<GigaChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new MaskedPrompt(List.of(), Map.of());
        }

        LinkedHashMap<String, String> originalToPlaceholder = new LinkedHashMap<>();
        String all = messages.stream()
                .filter(message -> message != null && message.content() != null)
                .map(GigaChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);

        collectStructuredValues(all, originalToPlaceholder);
        collectMatches(all, EMAIL, "EMAIL", originalToPlaceholder);
        collectMatches(all, PHONE, "PHONE", originalToPlaceholder);
        collectMatches(all, BANK_ACCOUNT, "BANK_ACCOUNT", originalToPlaceholder);
        collectMatches(all, CARD, "CARD", originalToPlaceholder);
        collectMatches(all, OGRN, "OGRN", originalToPlaceholder);
        collectMatches(all, INN, "INN", originalToPlaceholder);
        collectMatches(all, PASSPORT, "PASSPORT", originalToPlaceholder);
        collectMatches(all, VEHICLE_NUMBER, "VEHICLE", originalToPlaceholder);

        // Replace longer values first so an address/name containing a shorter
        // collected token cannot be partially corrupted.
        List<Map.Entry<String, String>> replacements = originalToPlaceholder.entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey(Comparator.comparingInt(String::length)).reversed())
                .toList();

        List<GigaChatMessage> maskedMessages = new ArrayList<>();
        for (GigaChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            String content = message.content();
            if (content != null) {
                for (Map.Entry<String, String> replacement : replacements) {
                    content = content.replace(replacement.getKey(), replacement.getValue());
                }
            }
            maskedMessages.add(new GigaChatMessage(message.role(), content));
        }

        LinkedHashMap<String, String> placeholderToOriginal = new LinkedHashMap<>();
        originalToPlaceholder.forEach((original, placeholder) -> placeholderToOriginal.put(placeholder, original));
        return new MaskedPrompt(List.copyOf(maskedMessages), Map.copyOf(placeholderToOriginal));
    }

    private void collectStructuredValues(String input, Map<String, String> values) {
        for (String field : STRUCTURED_FIELDS) {
            Pattern quotedValue = Pattern.compile(
                    "(?i)\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\""
            );
            Matcher matcher = quotedValue.matcher(input);
            while (matcher.find()) {
                // Keep the JSON-escaped representation. The provider receives
                // the placeholder inside a JSON string; restoring the escaped
                // original keeps the model response valid JSON even for values
                // such as ООО \"Экспедитор\".
                String raw = matcher.group(1).trim();
                if (!raw.isBlank() && !"null".equalsIgnoreCase(raw)) {
                    addValue(raw, field.toUpperCase(), values);
                }
            }
        }
    }

    private void collectMatches(String input, Pattern pattern, String category, Map<String, String> values) {
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            addValue(matcher.group(), category, values);
        }
    }

    private void addValue(String value, String category, Map<String, String> values) {
        if (value == null || value.isBlank() || values.containsKey(value)) {
            return;
        }
        String safeCategory = category.replaceAll("[^A-Z0-9_]+", "_");
        String placeholder = "__CT_PII_" + safeCategory + "_" + String.format("%03d", values.size() + 1) + "__";
        values.put(value, placeholder);
    }

    public record MaskedPrompt(
            List<GigaChatMessage> messages,
            Map<String, String> reverseMap
    ) {
        public String restore(String content) {
            if (content == null || content.isBlank() || reverseMap == null || reverseMap.isEmpty()) {
                ensureNoUnknownPlaceholders(content);
                return content;
            }
            String restored = content;
            for (Map.Entry<String, String> entry : reverseMap.entrySet()) {
                restored = restored.replace(entry.getKey(), entry.getValue());
            }
            ensureNoUnknownPlaceholders(restored);
            return restored;
        }

        public GigaChatChatResponse restore(GigaChatChatResponse response) {
            if (response == null || response.choices() == null) {
                return response;
            }
            List<GigaChatChatResponse.Choice> choices = response.choices().stream()
                    .map(choice -> {
                        if (choice == null || choice.message() == null) {
                            return choice;
                        }
                        return new GigaChatChatResponse.Choice(
                                choice.index(),
                                new GigaChatMessage(
                                        choice.message().role(),
                                        restore(choice.message().content())
                                )
                        );
                    })
                    .toList();
            return new GigaChatChatResponse(choices, response.usage());
        }

        private void ensureNoUnknownPlaceholders(String content) {
            if (content == null) {
                return;
            }
            Matcher matcher = PLACEHOLDER.matcher(content);
            if (matcher.find()) {
                throw new IllegalStateException(
                        "LLM response contains unresolved sensitive-data placeholder: " + matcher.group()
                );
            }
        }
    }
}
