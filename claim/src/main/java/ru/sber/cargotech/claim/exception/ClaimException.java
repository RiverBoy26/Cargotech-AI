package ru.sber.cargotech.claim.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ClaimException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private ClaimException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ClaimException notFound(String message) {
        return new ClaimException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND", message);
    }

    public static ClaimException conflict(String message) {
        return new ClaimException(HttpStatus.CONFLICT, "CLAIM_CONFLICT", message);
    }

    public static ClaimException validation(String message) {
        return new ClaimException(HttpStatus.UNPROCESSABLE_ENTITY, "CLAIM_VALIDATION_ERROR", message);
    }

    public static ClaimException forbidden(String message) {
        return new ClaimException(HttpStatus.FORBIDDEN, "CLAIM_ACCESS_DENIED", message);
    }
}
