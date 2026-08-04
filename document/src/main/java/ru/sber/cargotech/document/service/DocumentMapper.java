package ru.sber.cargotech.document.service;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.document.dto.ClaimTemplateResponse;
import ru.sber.cargotech.document.dto.ClaimTemplateVersionResponse;
import ru.sber.cargotech.document.dto.DocumentFileResponse;
import ru.sber.cargotech.document.dto.DocumentLinkResponse;
import ru.sber.cargotech.document.dto.DocumentResponse;
import ru.sber.cargotech.document.entity.ClaimTemplate;
import ru.sber.cargotech.document.entity.ClaimTemplateVersion;
import ru.sber.cargotech.document.entity.Document;
import ru.sber.cargotech.document.entity.DocumentFile;
import ru.sber.cargotech.document.entity.DocumentLink;

import java.util.List;

@Component
public class DocumentMapper {

    public DocumentResponse toResponse(
        Document document,
        List<DocumentLink> links
    ) {
        return new DocumentResponse(
            document.getId(),
            document.getOrganizationId(),
            document.getDocumentType(),
            document.getDocumentNumber(),
            document.getDocumentDate(),
            document.getDescription(),
            document.getSource(),
            document.getStatus(),
            document.getCreatedBy(),
            document.getCreatedAt(),
            document.getUpdatedAt(),
            toFileResponse(document.getFile()),
            links.stream().map(this::toLinkResponse).toList()
        );
    }

    public DocumentFileResponse toFileResponse(DocumentFile file) {
        return new DocumentFileResponse(
            file.getId(),
            file.getStorageProvider(),
            file.getBucketName(),
            file.getStorageKey(),
            file.getOriginalName(),
            file.getContentType(),
            file.getSizeBytes(),
            file.getChecksum(),
            file.getUploadedBy(),
            file.getUploadedAt()
        );
    }

    public DocumentLinkResponse toLinkResponse(DocumentLink link) {
        return new DocumentLinkResponse(
            link.getId(),
            link.getDocument().getId(),
            link.getEntityType(),
            link.getEntityId(),
            link.getLinkType(),
            link.getCreatedBy(),
            link.getCreatedAt()
        );
    }

    public ClaimTemplateResponse toTemplateResponse(
        ClaimTemplate template,
        List<ClaimTemplateVersion> versions
    ) {
        return new ClaimTemplateResponse(
            template.getId(),
            template.getOrganizationId(),
            template.getCode(),
            template.getName(),
            template.getClaimType(),
            template.getClientId(),
            template.getDescription(),
            template.isDefaultTemplate(),
            template.getPriority(),
            template.isActive(),
            template.getCreatedAt(),
            template.getUpdatedAt(),
            versions.stream().map(this::toTemplateVersionResponse).toList()
        );
    }

    public ClaimTemplateVersionResponse toTemplateVersionResponse(
        ClaimTemplateVersion version
    ) {
        return new ClaimTemplateVersionResponse(
            version.getId(),
            version.getTemplate().getId(),
            version.getVersionNumber(),
            version.getContentSha256(),
            version.getEncryptionKeyId(),
            version.getEncryptionAlgorithm(),
            version.getContentFormat(),
            version.getVariables(),
            version.getChangeComment(),
            version.isActive(),
            version.getCreatedBy(),
            version.getCreatedAt()
        );
    }
}
