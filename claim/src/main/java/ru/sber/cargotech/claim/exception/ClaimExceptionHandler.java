package ru.sber.cargotech.claim.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.sber.cargotech.claim.dto.ApiErrorResponse;

import java.time.OffsetDateTime;

@RestControllerAdvice(basePackages = "ru.sber.cargotech.claim")
public class ClaimExceptionHandler {

    @ExceptionHandler(ClaimException.class)
    public ResponseEntity<ApiErrorResponse> handleClaimException(ClaimException exception) {
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
