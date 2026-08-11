package ru.sber.cargotech.document.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import ru.sber.cargotech.document.dto.DocumentErrorResponse;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class DocumentExceptionHandler {

    @ExceptionHandler(DocumentException.class)
    public ResponseEntity<DocumentErrorResponse> handleDocumentException(
        DocumentException exception
    ) {
        return ResponseEntity
            .status(exception.status())
            .body(new DocumentErrorResponse(
                exception.code(),
                exception.getMessage(),
                OffsetDateTime.now()
            ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<DocumentErrorResponse> handleAccessDenied(
        AccessDeniedException exception
    ) {
        return error(
            HttpStatus.FORBIDDEN,
            "DOCUMENT_FORBIDDEN",
            "Недостаточно прав для операции с документами"
        );
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<DocumentErrorResponse> handleAuthenticationMissing(
        AuthenticationCredentialsNotFoundException exception
    ) {
        return error(
            HttpStatus.UNAUTHORIZED,
            "DOCUMENT_UNAUTHORIZED",
            "Требуется действительный access token"
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<DocumentErrorResponse> handleValidation(
        MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("; "));

        return error(
            HttpStatus.BAD_REQUEST,
            "DOCUMENT_VALIDATION_ERROR",
            message.isBlank() ? "Некорректный запрос" : message
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<DocumentErrorResponse> handleNotReadable(
        HttpMessageNotReadableException exception
    ) {
        return error(
            HttpStatus.BAD_REQUEST,
            "DOCUMENT_BAD_REQUEST",
            "Некорректное тело запроса"
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<DocumentErrorResponse> handleMaxUploadSize(
        MaxUploadSizeExceededException exception
    ) {
        return error(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "DOCUMENT_FILE_TOO_LARGE",
            "Размер файла превышает допустимый лимит"
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<DocumentErrorResponse> handleUnexpected(
        Exception exception
    ) {
        return error(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "DOCUMENT_INTERNAL_ERROR",
            "Внутренняя ошибка модуля document"
        );
    }

    private ResponseEntity<DocumentErrorResponse> error(
        HttpStatus status,
        String code,
        String message
    ) {
        return ResponseEntity
            .status(status)
            .body(new DocumentErrorResponse(
                code,
                message,
                OffsetDateTime.now()
            ));
    }
}
