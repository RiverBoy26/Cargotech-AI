package ru.sber.cargotech.ai.gigachat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GigaChatChatResponse(
        List<Choice> choices,
        Usage usage
) {
    public record Choice(
            Integer index,
            GigaChatMessage message
    ) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens")
            Integer promptTokens,

            @JsonProperty("completion_tokens")
            Integer completionTokens,

            @JsonProperty("total_tokens")
            Integer totalTokens
    ) {
    }

    public String firstContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }

        GigaChatMessage message = choices.get(0).message();

        if (message == null) {
            return null;
        }

        return message.content();
    }
}