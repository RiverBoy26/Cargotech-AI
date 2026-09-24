package ru.sber.cargotech.document.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.document.dto.InternalClaimDocumentReadinessResponse;
import ru.sber.cargotech.document.entity.DocumentLink;
import ru.sber.cargotech.document.enums.DocumentEntityType;
import ru.sber.cargotech.document.enums.DocumentStatus;
import ru.sber.cargotech.document.repository.DocumentLinkRepository;

import java.util.List;
import java.util.UUID;

@Service
public class DocumentReadinessService {

    private final DocumentLinkRepository linkRepository;

    public DocumentReadinessService(DocumentLinkRepository linkRepository) {
        this.linkRepository = linkRepository;
    }

    @Transactional(readOnly = true)
    public InternalClaimDocumentReadinessResponse getClaimReadiness(UUID claimId) {
        List<DocumentLink> links = linkRepository
            .findAllByEntityTypeAndEntityIdOrderByCreatedAtDesc(DocumentEntityType.CLAIM, claimId)
            .stream()
            .filter(link -> link.getDocument().getStatus() == DocumentStatus.ACTIVE)
            .toList();

        List<InternalClaimDocumentReadinessResponse.DocumentReference> documents = links.stream()
            .map(link -> new InternalClaimDocumentReadinessResponse.DocumentReference(
                link.getDocument().getId(),
                link.getDocument().getDocumentType().name(),
                link.getLinkType(),
                link.getDocument().getDocumentNumber(),
                link.getDocument().getDocumentDate()
            ))
            .toList();

        return new InternalClaimDocumentReadinessResponse(
            claimId,
            hasLinkType(links, "GENERATED_CLAIM"),
            hasLinkType(links, "CALCULATION_PDF"),
            hasLinkType(links, "CALCULATION_XLSX"),
            documents.size(),
            documents
        );
    }

    private boolean hasLinkType(List<DocumentLink> links, String expected) {
        return links.stream().anyMatch(link -> expected.equals(link.getLinkType()));
    }
}
