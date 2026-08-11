package ru.sber.cargotech.payment.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.sber.cargotech.payment.dto.PaymentErrorResponse;

import java.time.OffsetDateTime;

@RestControllerAdvice(basePackages = "ru.sber.cargotech.payment")
public class PaymentExceptionHandler {
    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<PaymentErrorResponse> handlePaymentException(
        PaymentException exception
    ) {
        return ResponseEntity
            .status(exception.getStatus())
            .body(new PaymentErrorResponse(
                exception.getCode(),
                exception.getMessage(),
                OffsetDateTime.now()
            ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PaymentErrorResponse> handleValidation(
        MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Некорректный запрос");

        return ResponseEntity.badRequest().body(
            new PaymentErrorResponse(
                "REQUEST_VALIDATION_ERROR",
                message,
                OffsetDateTime.now()
            )
        );
    }
}
