package ru.sber.cargotech.auth.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.sber.cargotech.auth.dto.ApiErrorResponse;

import java.time.OffsetDateTime;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthException(
        AuthException exception
    ) {
        return ResponseEntity.status(exception.getStatus()).body(
            new ApiErrorResponse(
                exception.getCode(),
                exception.getMessage(),
                OffsetDateTime.now()
            )
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleRequestValidation(
        MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Некорректные параметры запроса");

        return ResponseEntity.badRequest().body(
            new ApiErrorResponse(
                "REQUEST_VALIDATION_ERROR",
                message,
                OffsetDateTime.now()
            )
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintValidation(
        ConstraintViolationException exception
    ) {
        return ResponseEntity.badRequest().body(
            new ApiErrorResponse(
                "REQUEST_VALIDATION_ERROR",
                exception.getMessage(),
                OffsetDateTime.now()
            )
        );
    }
}
