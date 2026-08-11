package ru.sber.cargotech.ai.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException exception) {
        return errorResponse("BAD_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleConflict(IllegalStateException exception) {
        return errorResponse("CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(RestClientResponseException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, Object> handleUpstreamError(RestClientResponseException exception) {
        String message = exception.getMessage();

        if (exception.getResponseBodyAsString() != null && !exception.getResponseBodyAsString().isBlank()) {
            message = exception.getResponseBodyAsString();
        }

        return errorResponse("UPSTREAM_ERROR", message);
    }

    private Map<String, Object> errorResponse(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", code);
        result.put("message", message);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }
}
