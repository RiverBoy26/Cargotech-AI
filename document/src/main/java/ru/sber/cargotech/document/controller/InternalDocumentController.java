package ru.sber.cargotech.document.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.document.dto.InternalDocumentTextResponse;
import ru.sber.cargotech.document.dto.InternalClaimDocumentReadinessResponse;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.service.DocumentReadinessService;
import ru.sber.cargotech.document.service.DocumentTextExtractionService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/v1/documents")
public class InternalDocumentController {

    private final DocumentTextExtractionService extractionService;
    private final DocumentReadinessService readinessService;
    private final byte[] internalApiKey;

    public InternalDocumentController(
        DocumentTextExtractionService extractionService,
        DocumentReadinessService readinessService,
        @Value("${document.internal-api-key}") String internalApiKey
    ) {
        this.extractionService = extractionService;
        this.readinessService = readinessService;
        this.internalApiKey = internalApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/{documentId}/text")
    public InternalDocumentTextResponse getText(
        @PathVariable UUID documentId,
        @RequestHeader("X-Internal-Api-Key") String suppliedKey
    ) {
        validateKey(suppliedKey);
        return extractionService.getInternal(documentId);
    }

    @GetMapping("/claims/{claimId}/readiness")
    public InternalClaimDocumentReadinessResponse getClaimReadiness(
        @PathVariable UUID claimId,
        @RequestHeader("X-Internal-Api-Key") String suppliedKey
    ) {
        validateKey(suppliedKey);
        return readinessService.getClaimReadiness(claimId);
    }

    private void validateKey(String suppliedKey) {
        byte[] supplied = suppliedKey == null
            ? new byte[0]
            : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(internalApiKey, supplied)) {
            throw DocumentException.forbidden("Недействительный ключ внутреннего API");
        }
    }
}
