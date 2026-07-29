package ru.sber.cargotech.document.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.document.dto.ClaimTemplateOptionResponse;
import ru.sber.cargotech.document.dto.ClaimTemplateResponse;
import ru.sber.cargotech.document.dto.ClaimTemplateVersionResponse;
import ru.sber.cargotech.document.dto.CreateClaimTemplateRequest;
import ru.sber.cargotech.document.dto.CreateClaimTemplateVersionRequest;
import ru.sber.cargotech.document.dto.PageResponse;
import ru.sber.cargotech.document.dto.TemplatePreviewRequest;
import ru.sber.cargotech.document.dto.TemplatePreviewResponse;
import ru.sber.cargotech.document.security.CurrentDocumentUser;
import ru.sber.cargotech.document.security.CurrentDocumentUserProvider;
import ru.sber.cargotech.document.service.ClaimTemplateService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-templates")
public class DocumentTemplateController {

    private final ClaimTemplateService templateService;
    private final CurrentDocumentUserProvider currentUserProvider;

    public DocumentTemplateController(
        ClaimTemplateService templateService,
        CurrentDocumentUserProvider currentUserProvider
    ) {
        this.templateService = templateService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    public PageResponse<ClaimTemplateResponse> findAll(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        CurrentDocumentUser user = currentUserProvider.getCurrentUser();
        Pageable pageable = PageRequest.of(page, size);
        return templateService.findAll(pageable, user);
    }

    @GetMapping("/available")
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    public List<ClaimTemplateOptionResponse> findAvailable(
        @RequestParam String claimType,
        @RequestParam(required = false) UUID clientId
    ) {
        return templateService.findAvailable(
            claimType,
            clientId,
            currentUserProvider.getCurrentUser()
        );
    }

    @GetMapping("/{templateId}")
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    public ClaimTemplateResponse get(
        @PathVariable UUID templateId
    ) {
        return templateService.get(
            templateId,
            currentUserProvider.getCurrentUser()
        );
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_CREATE')")
    public ClaimTemplateResponse create(
        @Valid @RequestBody CreateClaimTemplateRequest request
    ) {
        return templateService.create(
            request,
            currentUserProvider.getCurrentUser()
        );
    }

    @PostMapping("/{templateId}/versions")
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_UPDATE')")
    public ClaimTemplateVersionResponse createVersion(
        @PathVariable UUID templateId,
        @Valid @RequestBody CreateClaimTemplateVersionRequest request
    ) {
        return templateService.createVersion(
            templateId,
            request,
            currentUserProvider.getCurrentUser()
        );
    }

    @PostMapping("/{templateId}/versions/{versionId}/activate")
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_UPDATE')")
    public ClaimTemplateVersionResponse activateVersion(
        @PathVariable UUID templateId,
        @PathVariable UUID versionId
    ) {
        return templateService.activate(
            templateId,
            versionId,
            currentUserProvider.getCurrentUser()
        );
    }

    @PostMapping("/{templateId}/preview")
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    public TemplatePreviewResponse preview(
        @PathVariable UUID templateId,
        @RequestBody(required = false) TemplatePreviewRequest request
    ) {
        return templateService.preview(
            templateId,
            request,
            currentUserProvider.getCurrentUser()
        );
    }

    @GetMapping("/{templateId}/versions/{versionId}/export")
    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    public ResponseEntity<String> export(
        @PathVariable UUID templateId,
        @PathVariable UUID versionId
    ) {
        ClaimTemplateService.ExportedTemplate exported = templateService.export(
            templateId,
            versionId,
            currentUserProvider.getCurrentUser()
        );

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(exported.filename(), StandardCharsets.UTF_8)
                    .build()
                    .toString()
            )
            .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .body(exported.content());
    }
}
