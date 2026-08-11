package ru.sber.cargotech.payment.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class PaymentException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    private PaymentException(
        HttpStatus status,
        String code,
        String message
    ) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static PaymentException notFound(String message) {
        return new PaymentException(
            HttpStatus.NOT_FOUND,
            "PAYMENT_NOT_FOUND",
            message
        );
    }

    public static PaymentException conflict(String message) {
        return new PaymentException(
            HttpStatus.CONFLICT,
            "PAYMENT_CONFLICT",
            message
        );
    }

    public static PaymentException unprocessable(String message) {
        return new PaymentException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "PAYMENT_VALIDATION_ERROR",
            message
        );
    }

    public static PaymentException forbidden(String message) {
        return new PaymentException(
            HttpStatus.FORBIDDEN,
            "PAYMENT_ACCESS_DENIED",
            message
        );
    }
}
