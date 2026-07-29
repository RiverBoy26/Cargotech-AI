package ru.sber.cargotech.document.service;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.document.exception.DocumentException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TemplateTextRenderer {

    private static final Pattern VARIABLE_PATTERN =
        Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}");
    private static final Object MISSING = new Object();

    public RenderedText render(
        String templateContent,
        Map<String, Object> data,
        boolean keepMissingVariables
    ) {
        if (templateContent == null || templateContent.isBlank()) {
            throw DocumentException.badRequest("Содержимое шаблона пустое");
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(templateContent);
        StringBuffer buffer = new StringBuffer();
        Set<String> variables = new LinkedHashSet<>();
        Set<String> missingVariables = new LinkedHashSet<>();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            variables.add(variableName);
            Object value = readValue(data, variableName);
            String replacement;

            if (value == MISSING || value == null) {
                missingVariables.add(variableName);
                replacement = keepMissingVariables
                    ? "{{" + variableName + "}}"
                    : "";
            } else {
                replacement = String.valueOf(value);
            }

            matcher.appendReplacement(
                buffer,
                Matcher.quoteReplacement(replacement)
            );
        }

        matcher.appendTail(buffer);
        return new RenderedText(
            buffer.toString(),
            new ArrayList<>(variables),
            new ArrayList<>(missingVariables)
        );
    }

    private Object readValue(Map<String, Object> data, String variableName) {
        if (data == null || data.isEmpty()) {
            return MISSING;
        }
        if (data.containsKey(variableName)) {
            return data.get(variableName);
        }

        Object current = data;
        for (String part : variableName.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return MISSING;
            }
            current = map.get(part);
        }
        return current;
    }

    public record RenderedText(
        String content,
        List<String> variables,
        List<String> missingVariables
    ) {
    }
}
