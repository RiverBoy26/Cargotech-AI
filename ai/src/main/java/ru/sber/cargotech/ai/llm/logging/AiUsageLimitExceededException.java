package ru.sber.cargotech.ai.llm.logging;

public class AiUsageLimitExceededException extends IllegalStateException {
    public AiUsageLimitExceededException(String message) {
        super(message);
    }
}
