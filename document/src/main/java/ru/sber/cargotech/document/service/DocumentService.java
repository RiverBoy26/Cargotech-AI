package ru.sber.cargotech.document.service;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.sber.cargotech.document.dto.DocumentLinkRequest;
import ru.sber.cargotech.document.dto.DocumentResponse;
import ru.sber.cargotech.document.dto.PageResponse;
import ru.sber.cargotech.document.dto.StoreGeneratedFileCommand;
import ru.sber.cargotech.document.entity.Document;
import ru.sber.cargotech.document.entity.DocumentFile;
import ru.sber.cargotech.document.entity.DocumentLink;
import ru.sber.cargotech.document.enums.DocumentEntityType;
import ru.sber.cargotech.document.enums.DocumentSource;
import ru.sber.cargotech.document.enums.DocumentStatus;
import ru.sber.cargotech.document.enums.DocumentType;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.repository.DocumentFileRepository;
import ru.sber.cargotech.document.repository.DocumentLinkRepository;
import ru.sber.cargotech.document.repository.DocumentRepository;
import ru.sber.cargotech.document.security.CurrentDocumentUser;
import ru.sber.cargotech.document.storage.LocalDocumentStorageService;
import ru.sber.cargotech.document.storage.StoredFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentFileRepository fileRepository;
    private final DocumentLinkRepository linkRepository;
    private final LocalDocumentStorageService storageService;
    private final DocumentMapper mapper;
    private final DocumentTextExtractionService textExtractionService;

    public DocumentService(
        DocumentRepository documentRepository,
        DocumentFileRepository fileRepository,
        DocumentLinkRepository linkRepository,
        LocalDocumentStorageService storageService,
        DocumentMapper mapper,
        DocumentTextExtractionService textExtractionService
    ) {
        this.documentRepository = documentRepository;
        this.fileRepository = fileRepository;
        this.linkRepository = linkRepository;
        this.storageService = storageService;
        this.mapper = mapper;
        this.textExtractionService = textExtractionService;
    }

    @PreAuthorize("hasAuthority('DOCUMENT_UPLOAD')")
    @Transactional
    public DocumentResponse upload(
        MultipartFile multipartFile,
        DocumentType documentType,
        String documentNumber,
        LocalDate documentDate,
        String description,
        DocumentEntityType entityType,
        UUID entityId,
        String linkType,
        CurrentDocumentUser user
    ) {
        StoredFile storedFile = storageService.storeMultipart(
            user.organizationId(),
            multipartFile
        );

        Document document = createDocument(
            user.organizationId(),
            user.userId(),
            storedFile,
            documentType,
            documentNumber,
            documentDate,
            description,
            DocumentSource.UPLOADED
        );

        if (entityType != null && entityId != null) {
            createLink(document, entityType, entityId, linkType, user.userId());
        }

        if (document.getDocumentType() == DocumentType.CONTRACT) {
            textExtractionService.extractAndSave(document);
        }

        return toResponse(document);
    }

    @PreAuthorize("hasAuthority('DOCUMENT_GENERATE')")
    @Transactional
    public Document storeGenerated(StoreGeneratedFileCommand command) {
        StoredFile storedFile = storageService.storeBytes(
            command.organizationId(),
            command.content(),
            command.filename(),
            command.contentType()
        );

        return createDocument(
            command.organizationId(),
            command.userId(),
            storedFile,
            command.documentType(),
            command.documentNumber(),
            command.documentDate(),
            command.description(),
            DocumentSource.GENERATED
        );
    }

    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    @Transactional(readOnly = true)
    public DocumentResponse get(
        UUID documentId,
        CurrentDocumentUser user
    ) {
        Document document = getActiveDocument(
            documentId,
            user.organizationId()
        );
        return toResponse(document);
    }

    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> findAll(
        DocumentType documentType,
        DocumentEntityType entityType,
        UUID entityId,
        Pageable pageable,
        CurrentDocumentUser user
    ) {
        if (entityType != null && entityId != null) {
            List<DocumentResponse> responses = linkRepository
                .findAllByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
                .stream()
                .map(DocumentLink::getDocument)
                .filter(document -> document.getOrganizationId().equals(user.organizationId()))
                .filter(document -> document.getStatus() != DocumentStatus.DELETED)
                .filter(document -> documentType == null || document.getDocumentType() == documentType)
                .map(this::toResponse)
                .toList();

            return PageResponse.from(toPage(responses, pageable));
        }

        Page<Document> documents = documentType == null
            ? documentRepository.findAllByOrganizationIdAndStatusNot(
                user.organizationId(),
                DocumentStatus.DELETED,
                pageable
            )
            : documentRepository.findAllByOrganizationIdAndDocumentTypeAndStatusNot(
                user.organizationId(),
                documentType,
                DocumentStatus.DELETED,
                pageable
            );

        return PageResponse.from(documents.map(this::toResponse));
    }

    @PreAuthorize("hasAuthority('DOCUMENT_DOWNLOAD')")
    @Transactional(readOnly = true)
    public DocumentDownload download(
        UUID documentId,
        CurrentDocumentUser user
    ) {
        Document document = getActiveDocument(documentId, user.organizationId());
        Resource resource = storageService.load(document.getFile().getStorageKey());

        return new DocumentDownload(
            resource,
            document.getFile().getOriginalName(),
            document.getFile().getContentType(),
            document.getFile().getSizeBytes()
        );
    }

    @PreAuthorize("hasAuthority('DOCUMENT_LINK')")
    @Transactional
    public DocumentResponse addLink(
        UUID documentId,
        DocumentLinkRequest request,
        CurrentDocumentUser user
    ) {
        Document document = getActiveDocument(documentId, user.organizationId());
        createLink(
            document,
            request.entityType(),
            request.entityId(),
            request.linkType(),
            user.userId()
        );
        return toResponse(document);
    }

    @PreAuthorize("hasAuthority('DOCUMENT_LINK')")
    @Transactional
    public void deleteLink(
        UUID documentId,
        UUID linkId,
        CurrentDocumentUser user
    ) {
        Document document = getActiveDocument(documentId, user.organizationId());
        DocumentLink link = linkRepository.findByIdAndDocument_Id(linkId, document.getId())
            .orElseThrow(() -> DocumentException.notFound(
                "Связь документа %s не найдена".formatted(linkId)
            ));
        linkRepository.delete(link);
    }

    @PreAuthorize("hasAuthority('DOCUMENT_DELETE')")
    @Transactional
    public void markDeleted(
        UUID documentId,
        CurrentDocumentUser user
    ) {
        Document document = getActiveDocument(documentId, user.organizationId());
        document.setStatus(DocumentStatus.DELETED);
        documentRepository.save(document);
    }

    public Document getActiveDocument(UUID documentId, UUID organizationId) {
        return documentRepository
            .findByIdAndOrganizationIdAndStatusNot(
                documentId,
                organizationId,
                DocumentStatus.DELETED
            )
            .orElseThrow(() -> DocumentException.notFound(
                "Документ %s не найден".formatted(documentId)
            ));
    }

    private Document createDocument(
        UUID organizationId,
        UUID userId,
        StoredFile storedFile,
        DocumentType documentType,
        String documentNumber,
        LocalDate documentDate,
        String description,
        DocumentSource source
    ) {
        DocumentFile file = new DocumentFile();
        file.setOrganizationId(organizationId);
        file.setStorageProvider(storedFile.provider());
        file.setBucketName(storedFile.bucketName());
        file.setStorageKey(storedFile.storageKey());
        file.setOriginalName(storedFile.originalName());
        file.setContentType(storedFile.contentType());
        file.setSizeBytes(storedFile.sizeBytes());
        file.setChecksum(storedFile.checksum());
        file.setUploadedBy(userId);
        file = fileRepository.save(file);

        Document document = new Document();
        document.setOrganizationId(organizationId);
        document.setFile(file);
        document.setDocumentType(documentType == null ? DocumentType.OTHER : documentType);
        document.setDocumentNumber(documentNumber);
        document.setDocumentDate(documentDate);
        document.setDescription(description);
        document.setSource(source);
        document.setStatus(DocumentStatus.ACTIVE);
        document.setCreatedBy(userId);

        return documentRepository.save(document);
    }

    private void createLink(
        Document document,
        DocumentEntityType entityType,
        UUID entityId,
        String linkType,
        UUID userId
    ) {
        String resolvedLinkType = linkType == null || linkType.isBlank()
            ? "ATTACHMENT"
            : linkType;

        if (linkRepository.existsByDocument_IdAndEntityTypeAndEntityIdAndLinkType(
            document.getId(),
            entityType,
            entityId,
            resolvedLinkType
        )) {
            return;
        }

        DocumentLink link = new DocumentLink();
        link.setDocument(document);
        link.setEntityType(entityType);
        link.setEntityId(entityId);
        link.setLinkType(resolvedLinkType);
        link.setCreatedBy(userId);
        linkRepository.save(link);
    }

    private DocumentResponse toResponse(Document document) {
        List<DocumentLink> links = linkRepository.findAllByDocument_Id(document.getId());
        return mapper.toResponse(document, links);
    }

    private Page<DocumentResponse> toPage(
        List<DocumentResponse> responses,
        Pageable pageable
    ) {
        int start = Math.toIntExact(pageable.getOffset());
        int end = Math.min(start + pageable.getPageSize(), responses.size());
        List<DocumentResponse> pageContent = start > responses.size()
            ? List.of()
            : responses.subList(start, end);
        return new PageImpl<>(pageContent, pageable, responses.size());
    }

    public record DocumentDownload(
        Resource resource,
        String filename,
        String contentType,
        long sizeBytes
    ) {
    }
}
