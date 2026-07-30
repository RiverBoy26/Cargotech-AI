package ru.sber.cargotech.ai.guardrail;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CitationTextMatcher {

    private static final Pattern UNSUPPORTED_INSTANCE_QUALIFIER = Pattern.compile(
            "(?iu)(?<!\\p{L})(?:оригинал\\p{L}*|копи(?:я|и|ю|ей|ею|ями|ях))(?!\\p{L})"
    );
    private static final Pattern CONTRACT_MARKER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:пп?\\.|пункт\\p{L}*|§)\\s*"
    );
    private static final Pattern ARTICLE_MARKER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:ст\\.|стат\\p{L}*)\\s*"
    );
    private static final Pattern POINT_MARKER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:пп?\\.|пункт\\p{L}*)\\s*"
    );

    private CitationTextMatcher() {
    }

    public static boolean containsUnsupportedInstanceQualifier(String... values) {
        if (values == null) return false;
        for (String value : values) {
            if (value != null && UNSUPPORTED_INSTANCE_QUALIFIER.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsContractClauseReference(String text, String clauseNumber) {
        if (!hasText(text) || !hasText(clauseNumber)) return false;
        String canonicalText = text.replace(',', '.');
        String canonicalNumber = clauseNumber.trim().replace(',', '.');
        Pattern numberPattern = Pattern.compile(
                "(?<![\\d.])" + Pattern.quote(canonicalNumber) + "(?![\\d.])"
        );

        Matcher numberMatcher = numberPattern.matcher(canonicalText);
        while (numberMatcher.find()) {
            int from = Math.max(0, numberMatcher.start() - 100);
            String prefix = canonicalText.substring(from, numberMatcher.start());
            if (CONTRACT_MARKER.matcher(prefix).find()) return true;
        }
        return false;
    }

    public static boolean containsLegalReference(String text, String lawCode, String article) {
        if (!hasText(text) || !hasText(lawCode) || !hasText(article)) return false;

        String referenceNumber = article.replace(',', '.').replaceAll("[^0-9.\\-]", "");
        if (!hasText(referenceNumber)) return false;

        boolean pointReference = normalize(article).contains("пункт")
                || normalize(article).matches(".*(?:^| )п(?: |$).*")
                || normalize(lawCode).contains("правил");
        Pattern markerPattern = pointReference ? POINT_MARKER : ARTICLE_MARKER;
        Pattern numberPattern = Pattern.compile(
                "(?<![\\d.])" + Pattern.quote(referenceNumber) + "(?![\\d.])"
        );

        String canonicalText = text.replace(',', '.');
        Matcher numberMatcher = numberPattern.matcher(canonicalText);
        while (numberMatcher.find()) {
            int markerFrom = Math.max(0, numberMatcher.start() - 100);
            String prefix = canonicalText.substring(markerFrom, numberMatcher.start());
            if (!markerPattern.matcher(prefix).find()) continue;

            int lawFrom = Math.max(0, numberMatcher.start() - 120);
            int lawTo = Math.min(canonicalText.length(), numberMatcher.end() + 200);
            if (containsLawCode(canonicalText.substring(lawFrom, lawTo), lawCode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLawCode(String text, String lawCode) {
        String normalizedText = normalize(text);
        String normalizedCode = normalize(lawCode);
        if (normalizedText.contains(normalizedCode)) return true;

        if (normalizedCode.contains("гк")) {
            return normalizedText.contains("гк рф")
                    || (normalizedText.contains("гражданск") && normalizedText.contains("кодекс"));
        }
        if (normalizedCode.contains("апк")) {
            return normalizedText.contains("апк рф")
                    || (normalizedText.contains("арбитражн")
                    && normalizedText.contains("процессуальн")
                    && normalizedText.contains("кодекс"));
        }
        if (normalizedCode.contains("гпк")) {
            return normalizedText.contains("гпк рф")
                    || (normalizedText.contains("гражданск")
                    && normalizedText.contains("процессуальн")
                    && normalizedText.contains("кодекс"));
        }
        if (normalizedCode.contains("тк")) {
            return normalizedText.contains("тк рф")
                    || (normalizedText.contains("трудов") && normalizedText.contains("кодекс"));
        }
        if (normalizedCode.contains("нк")) {
            return normalizedText.contains("нк рф")
                    || (normalizedText.contains("налогов") && normalizedText.contains("кодекс"));
        }
        if (normalizedCode.contains("коап")) {
            return normalizedText.contains("коап рф")
                    || (normalizedText.contains("административн")
                    && normalizedText.contains("правонаруш"));
        }
        if (normalizedCode.contains("уат")) {
            return normalizedText.contains("уат")
                    || (normalizedText.contains("устав")
                    && normalizedText.contains("автомобильн")
                    && normalizedText.contains("транспорт"));
        }
        if (normalizedCode.contains("правил")) {
            String number = lawCode.replaceAll("\\D", "");
            return normalizedText.contains("правил")
                    && (number.isBlank() || normalizedText.contains(number));
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('—', '-')
                .replace('–', '-')
                .replaceAll("[\\p{Punct}«»„“”]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
