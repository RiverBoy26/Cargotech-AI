package ru.sber.cargotech.document.controller;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.sber.cargotech.document.dto.DocumentLinkRequest;
import ru.sber.cargotech.document.dto.DocumentEmailDeliveryResponse;
import ru.sber.cargotech.document.dto.DocumentResponse;
import ru.sber.cargotech.document.dto.GenerateClaimDocumentRequest;
import ru.sber.cargotech.document.dto.GenerateDocumentResponse;
import ru.sber.cargotech.document.dto.PageResponse;
import ru.sber.cargotech.document.dto.SendDocumentEmailRequest;
import ru.sber.cargotech.document.enums.DocumentEntityType;
import ru.sber.cargotech.document.enums.DocumentType;
import ru.sber.cargotech.document.security.CurrentDocumentUser;
import ru.sber.cargotech.document.security.CurrentDocumentUserProvider;
import ru.sber.cargotech.document.service.ClaimDocumentGenerationService;
import ru.sber.cargotech.document.service.DocumentService;
import ru.sber.cargotech.document.service.DocumentEmailService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final ClaimDocumentGenerationService generationService;
    private final DocumentEmailService emailService;
    private final CurrentDocumentUserProvider currentUserProvider;

    public DocumentController(
        DocumentService documentService,
        ClaimDocumentGenerationService generationService,
        DocumentEmailService emailService,
        CurrentDocumentUserProvider currentUserProvider
    ) {
        this.documentService = documentService;
        this.generationService = generationService;
        this.emailService = emailService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(
        value = "/upload",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasAuthority('DOCUMENT_UPLOAD')")
    public DocumentResponse upload(
        @RequestPart("file") MultipartFile file,
        @RequestParam DocumentType documentType,
        @RequestParam(required = false) String documentNumber,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate documentDate,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) DocumentEntityType entityType,
        @RequestParam(required = false) UUID entityId,
        @RequestParam(required = false) String linkType
    ) {
        CurrentDocumentUser user = currentUserProvider.getCurrentUser();
        return documentService.upload(
            file,
            documentType,
            documentNumber,
            documentDate,
            description,
            entityType,
            entityId,
            linkType,
            user
        );
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    public PageResponse<DocumentResponse> findAll(
        @RequestParam(required = false) DocumentType documentType,
        @RequestParam(required = false) DocumentEntityType entityType,
        @RequestParam(required = false) UUID entityId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentDocumentUser user = currentUserProvider.getCurrentUser();
        Pageable pageable = PageRequest.of(page, size);
        return documentService.findAll(
            documentType,
            entityType,
            entityId,
            pageable,
            user
        );
    }

    @GetMapping("/{documentId}")
    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    public DocumentResponse get(
        @PathVariable UUID documentId
    ) {
        return documentService.get(
            documentId,
            currentUserProvider.getCurrentUser()
        );
    }

    @GetMapping("/{documentId}/download")
    @PreAuthorize("hasAuthority('DOCUMENT_DOWNLOAD')")
    public ResponseEntity<Resource> download(
        @PathVariable UUID documentId
    ) {
        DocumentService.DocumentDownload download = documentService.download(
            documentId,
            currentUserProvider.getCurrentUser()
        );

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(download.filename(), StandardCharsets.UTF_8)
                    .build()
                    .toString()
            )
            .contentType(MediaType.parseMediaType(download.contentType()))
            .contentLength(download.sizeBytes())
            .body(download.resource());
    }

    @PostMapping("/{documentId}/links")
    @PreAuthorize("hasAuthority('DOCUMENT_LINK')")
    public DocumentResponse addLink(
        @PathVariable UUID documentId,
        @Valid @RequestBody DocumentLinkRequest request
    ) {
        return documentService.addLink(
            documentId,
            request,
            currentUserProvider.getCurrentUser()
        );
    }

    @DeleteMapping("/{documentId}/links/{linkId}")
    @PreAuthorize("hasAuthority('DOCUMENT_LINK')")
    public ResponseEntity<Void> deleteLink(
        @PathVariable UUID documentId,
        @PathVariable UUID linkId
    ) {
        documentService.deleteLink(
            documentId,
            linkId,
            currentUserProvider.getCurrentUser()
        );
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{documentId}")
    @PreAuthorize("hasAuthority('DOCUMENT_DELETE')")
    public ResponseEntity<Void> delete(
        @PathVariable UUID documentId
    ) {
        documentService.markDeleted(
            documentId,
            currentUserProvider.getCurrentUser()
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/generate/claim")
    @PreAuthorize("hasAuthority('DOCUMENT_GENERATE')")
    public GenerateDocumentResponse generateClaim(
        @Valid @RequestBody GenerateClaimDocumentRequest request
    ) {
        return generationService.generateClaimDocument(
            request,
            currentUserProvider.getCurrentUser()
        );
    }

    @PostMapping("/{documentId}/send-email")
    @PreAuthorize("hasAuthority('DOCUMENT_SEND')")
    public DocumentEmailDeliveryResponse sendEmail(
        @PathVariable UUID documentId,
        @Valid @RequestBody SendDocumentEmailRequest request
    ) {
        return emailService.send(
            documentId,
            request,
            currentUserProvider.getCurrentUser()
        );
    }

    @GetMapping("/{documentId}/deliveries")
    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    public List<DocumentEmailDeliveryResponse> deliveries(
        @PathVariable UUID documentId
    ) {
        return emailService.findAll(
            documentId,
            currentUserProvider.getCurrentUser()
        );
    }
}
