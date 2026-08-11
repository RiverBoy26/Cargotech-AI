package ru.sber.cargotech.ai.security;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SensitiveDataMasker {

    private static final String MASK = "***";

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*:\\s*(?:basic|bearer)\\s+)[a-z0-9+/=._-]+"
    );
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+7|8)[\\s(-]?\\d{3}[\\s)-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}(?!\\d)");
    private static final Pattern PASSPORT = Pattern.compile("(?<!\\d)\\d{4}\\s?\\d{6}(?!\\d)");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("(?<!\\d)\\d{20}(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)");
    private static final Pattern INN = Pattern.compile("(?<!\\d)(?:\\d{10}|\\d{12})(?!\\d)");
    private static final Pattern OGRN = Pattern.compile("(?<!\\d)(?:\\d{13}|\\d{15})(?!\\d)");
    private static final Pattern VEHICLE_NUMBER = Pattern.compile("(?iu)(?<![А-ЯA-Z0-9])[АВЕКМНОРСТУХABEKMHOPCTYX]\\s?\\d{3}\\s?[АВЕКМНОРСТУХABEKMHOPCTYX]{2}\\s?\\d{2,3}(?![А-ЯA-Z0-9])");
    private static final Pattern MONEY_IN_TEXT = Pattern.compile("(?iu)(?<!\\d)\\d[\\d \\u00A0]{0,18}(?:[,.]\\d{1,2})?\\s*(?:руб(?:лей|ля|ль|\\.)?|₽)");

    private static final List<String> SENSITIVE_JSON_FIELDS = List.of(
            "name",
            "inn",
            "legal_address",
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
            "vehicle_plate",
            "principal_debt",
            "penalty_rate_text",
            "penalty_amount",
            "total_amount",
            "formula_text"
    );

    public String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String masked = value;
        masked = AUTHORIZATION.matcher(masked).replaceAll("$1" + MASK);
        masked = maskJsonFields(masked);
        masked = EMAIL.matcher(masked).replaceAll("[EMAIL]");
        masked = PHONE.matcher(masked).replaceAll("[PHONE]");
        masked = CARD.matcher(masked).replaceAll("[CARD]");
        masked = BANK_ACCOUNT.matcher(masked).replaceAll("[BANK_ACCOUNT]");
        masked = OGRN.matcher(masked).replaceAll("[OGRN]");
        masked = INN.matcher(masked).replaceAll("[INN]");
        masked = PASSPORT.matcher(masked).replaceAll("[PASSPORT]");
        masked = VEHICLE_NUMBER.matcher(masked).replaceAll("[VEHICLE_NUMBER]");
        masked = MONEY_IN_TEXT.matcher(masked).replaceAll("[AMOUNT]");
        return masked;
    }

    private String maskJsonFields(String input) {
        String result = input;

        for (String field : SENSITIVE_JSON_FIELDS) {
            Pattern quotedValue = Pattern.compile(
                    "(?i)(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\")((?:\\\\.|[^\\\"\\\\])*)(\\\")"
            );
            result = replaceGroup(quotedValue, result, MASK);

            Pattern scalarValue = Pattern.compile(
                    "(?i)(\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*)(-?\\d+(?:[.,]\\d+)?|true|false|null)"
            );
            result = scalarValue.matcher(result).replaceAll("$1\\\"" + MASK + "\\\"");
        }

        return result;
    }

    private String replaceGroup(Pattern pattern, String input, String replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(matcher.group(1) + replacement + matcher.group(3))
            );
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
