package ru.sber.cargotech.document.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.document.client.ClaimCalculationClient;
import ru.sber.cargotech.document.dto.GenerateClaimDocumentRequest;
import ru.sber.cargotech.document.dto.GenerateDocumentResponse;
import ru.sber.cargotech.document.dto.StoreGeneratedFileCommand;
import ru.sber.cargotech.document.entity.Document;
import ru.sber.cargotech.document.entity.DocumentGenerationLog;
import ru.sber.cargotech.document.entity.DocumentLink;
import ru.sber.cargotech.document.enums.DocumentEntityType;
import ru.sber.cargotech.document.enums.DocumentType;
import ru.sber.cargotech.document.enums.GeneratedDocumentType;
import ru.sber.cargotech.document.enums.GenerationStatus;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.repository.DocumentGenerationLogRepository;
import ru.sber.cargotech.document.repository.DocumentLinkRepository;
import ru.sber.cargotech.document.security.CurrentDocumentUser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ClaimDocumentGenerationService {

    private static final String DOCX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final ClaimTemplateService templateService;
    private final ClaimDocxRenderer docxRenderer;
    private final ClaimPdfRenderer pdfRenderer;
    private final DocumentService documentService;
    private final DocumentLinkRepository linkRepository;
    private final DocumentGenerationLogRepository generationLogRepository;
    private final ClaimCalculationClient calculationClient;

    public ClaimDocumentGenerationService(
        ClaimTemplateService templateService,
        ClaimDocxRenderer docxRenderer,
        ClaimPdfRenderer pdfRenderer,
        DocumentService documentService,
        DocumentLinkRepository linkRepository,
        DocumentGenerationLogRepository generationLogRepository,
        ClaimCalculationClient calculationClient
    ) {
        this.templateService = templateService;
        this.docxRenderer = docxRenderer;
        this.pdfRenderer = pdfRenderer;
        this.documentService = documentService;
        this.linkRepository = linkRepository;
        this.generationLogRepository = generationLogRepository;
        this.calculationClient = calculationClient;
    }

    @PreAuthorize("hasAuthority('DOCUMENT_GENERATE')")
    @Transactional
    public GenerateDocumentResponse generateClaimDocument(
        GenerateClaimDocumentRequest request,
        CurrentDocumentUser user
    ) {
        GeneratedDocumentType outputType = request.outputType() == null
            ? GeneratedDocumentType.CLAIM_DOCX
            : request.outputType();

        if (outputType != GeneratedDocumentType.CLAIM_DOCX
            && outputType != GeneratedDocumentType.CLAIM_PDF) {
            throw DocumentException.badRequest(
                "Поддерживается генерация только CLAIM_DOCX и CLAIM_PDF"
            );
        }

        DocumentSource source = resolveSource(request, user.organizationId());

        DocumentGenerationLog log = startLog(
            request,
            user,
            outputType,
            source.templateVersionId()
        );

        try {
            byte[] content = render(
                outputType,
                source.content(),
                request.data()
            );

            Document document = documentService.storeGenerated(
                new StoreGeneratedFileCommand(
                    user.organizationId(),
                    user.userId(),
                    content,
                    filename(request.claimId(), outputType),
                    contentType(outputType),
                    documentType(outputType),
                    request.documentNumber(),
                    request.documentDate(),
                    request.description()
                )
            );

            linkDocumentToClaim(document, request.claimId(), user.userId());
            storeCalculationAppendix(request, user, "pdf");
            storeCalculationAppendix(request, user, "xlsx");
            log.setDocument(document);
            log.setStatus(GenerationStatus.COMPLETED);
            generationLogRepository.save(log);

            return new GenerateDocumentResponse(
                log.getId(),
                document.getId(),
                document.getFile().getId(),
                request.claimId(),
                source.templateId(),
                source.templateVersionId(),
                outputType,
                log.getStatus(),
                "/api/v1/documents/" + document.getId() + "/download"
            );
        } catch (RuntimeException exception) {
            log.setStatus(GenerationStatus.FAILED);
            log.setErrorMessage(safeError(exception));
            generationLogRepository.save(log);
            throw exception;
        }
    }

    private void storeCalculationAppendix(
        GenerateClaimDocumentRequest request,
        CurrentDocumentUser user,
        String format
    ) {
        ClaimCalculationClient.CalculationAttachment attachment =
            calculationClient.download(request.claimId(), format);
        Document appendix = documentService.storeGenerated(
            new StoreGeneratedFileCommand(
                user.organizationId(),
                user.userId(),
                attachment.content(),
                attachment.filename(),
                attachment.contentType(),
                DocumentType.CALCULATION_APPENDIX,
                request.documentNumber(),
                request.documentDate(),
                "Расчёт суммы претензии (" + format.toUpperCase() + ")"
            )
        );
        DocumentLink link = new DocumentLink();
        link.setDocument(appendix);
        link.setEntityType(DocumentEntityType.CLAIM);
        link.setEntityId(request.claimId());
        link.setLinkType("CALCULATION_" + format.toUpperCase());
        link.setCreatedBy(user.userId());
        linkRepository.save(link);
    }

    private DocumentSource resolveSource(
        GenerateClaimDocumentRequest request,
        UUID organizationId
    ) {
        boolean templateRequested = request.templateId() != null
            || (request.templateCode() != null && !request.templateCode().isBlank());

        if (templateRequested) {
            ClaimTemplateService.ResolvedTemplate template = templateService
                .resolveActiveTemplate(
                    organizationId,
                    request.templateId(),
                    request.templateVersionId(),
                    request.templateCode()
                );
            return new DocumentSource(
                template.decryptedContent(),
                template.template().getId(),
                template.version().getId()
            );
        }

        if (request.templateVersionId() != null) {
            throw DocumentException.badRequest(
                "templateVersionId нельзя использовать без templateId или templateCode"
            );
        }
        if (request.claimText() == null || request.claimText().isBlank()) {
            throw DocumentException.badRequest(
                "Для формирования без шаблона передайте непустой claimText"
            );
        }

        return new DocumentSource(request.claimText(), null, null);
    }

    private byte[] render(
        GeneratedDocumentType outputType,
        String templateContent,
        Map<String, Object> data
    ) {
        return switch (outputType) {
            case CLAIM_DOCX -> docxRenderer.render(templateContent, data);
            case CLAIM_PDF -> pdfRenderer.render(templateContent, data);
            default -> throw DocumentException.badRequest(
                "Неподдерживаемый формат документа"
            );
        };
    }

    private DocumentGenerationLog startLog(
        GenerateClaimDocumentRequest request,
        CurrentDocumentUser user,
        GeneratedDocumentType outputType,
        UUID templateVersionId
    ) {
        DocumentGenerationLog log = new DocumentGenerationLog();
        log.setOrganizationId(user.organizationId());
        log.setClaimId(request.claimId());
        log.setOutputType(outputType);
        log.setSourceVersionId(templateVersionId);
        log.setRequestSnapshot(safeSnapshot(request));
        log.setStatus(GenerationStatus.PROCESSING);
        log.setGeneratedBy(user.userId());
        return generationLogRepository.save(log);
    }

    private void linkDocumentToClaim(
        Document document,
        UUID claimId,
        UUID userId
    ) {
        DocumentLink link = new DocumentLink();
        link.setDocument(document);
        link.setEntityType(DocumentEntityType.CLAIM);
        link.setEntityId(claimId);
        link.setLinkType("GENERATED_CLAIM");
        link.setCreatedBy(userId);
        linkRepository.save(link);
    }

    private Map<String, Object> safeSnapshot(GenerateClaimDocumentRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("templateId", request.templateId());
        snapshot.put("templateVersionId", request.templateVersionId());
        snapshot.put("templateCode", request.templateCode());
        snapshot.put("claimId", request.claimId());
        snapshot.put("outputType", request.outputType());
        snapshot.put("documentNumber", request.documentNumber());
        snapshot.put("rawClaimText", request.claimText() != null);
        snapshot.put(
            "claimTextLength",
            request.claimText() == null ? 0 : request.claimText().length()
        );
        snapshot.put(
            "dataKeys",
            request.data() == null ? java.util.List.of() : request.data().keySet()
        );
        return snapshot;
    }

    private String filename(UUID claimId, GeneratedDocumentType outputType) {
        String extension = outputType == GeneratedDocumentType.CLAIM_PDF
            ? ".pdf"
            : ".docx";
        return "claim_" + claimId + extension;
    }

    private String contentType(GeneratedDocumentType outputType) {
        return outputType == GeneratedDocumentType.CLAIM_PDF
            ? PDF_CONTENT_TYPE
            : DOCX_CONTENT_TYPE;
    }

    private DocumentType documentType(GeneratedDocumentType outputType) {
        return outputType == GeneratedDocumentType.CLAIM_PDF
            ? DocumentType.CLAIM_PDF
            : DocumentType.CLAIM_DOCX;
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    private record DocumentSource(
        String content,
        UUID templateId,
        UUID templateVersionId
    ) {
    }
}
