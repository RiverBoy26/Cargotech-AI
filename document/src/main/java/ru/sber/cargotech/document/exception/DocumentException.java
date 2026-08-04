package ru.sber.cargotech.document.exception;

import org.springframework.http.HttpStatus;

public class DocumentException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private DocumentException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static DocumentException unauthorized(String message) {
        return new DocumentException(HttpStatus.UNAUTHORIZED, "DOCUMENT_UNAUTHORIZED", message);
    }

    public static DocumentException forbidden(String message) {
        return new DocumentException(HttpStatus.FORBIDDEN, "DOCUMENT_FORBIDDEN", message);
    }

    public static DocumentException notFound(String message) {
        return new DocumentException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", message);
    }

    public static DocumentException badRequest(String message) {
        return new DocumentException(HttpStatus.BAD_REQUEST, "DOCUMENT_BAD_REQUEST", message);
    }

    public static DocumentException conflict(String message) {
        return new DocumentException(HttpStatus.CONFLICT, "DOCUMENT_CONFLICT", message);
    }

    public static DocumentException unprocessable(String message) {
        return new DocumentException(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_UNPROCESSABLE", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
