package ru.sber.cargotech.auth.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AuthException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private AuthException(
        HttpStatus status,
        String code,
        String message
    ) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AuthException unauthorized(String message) {
        return new AuthException(
            HttpStatus.UNAUTHORIZED,
            "AUTH_UNAUTHORIZED",
            message
        );
    }

    public static AuthException forbidden(String message) {
        return new AuthException(
            HttpStatus.FORBIDDEN,
            "AUTH_FORBIDDEN",
            message
        );
    }

    public static AuthException notFound(String message) {
        return new AuthException(
            HttpStatus.NOT_FOUND,
            "AUTH_NOT_FOUND",
            message
        );
    }

    public static AuthException conflict(String message) {
        return new AuthException(
            HttpStatus.CONFLICT,
            "AUTH_CONFLICT",
            message
        );
    }

    public static AuthException validation(String message) {
        return new AuthException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "AUTH_VALIDATION_ERROR",
            message
        );
    }
}
