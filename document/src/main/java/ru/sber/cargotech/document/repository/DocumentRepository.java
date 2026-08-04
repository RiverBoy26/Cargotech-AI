package ru.sber.cargotech.document.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.document.entity.Document;
import ru.sber.cargotech.document.enums.DocumentStatus;
import ru.sber.cargotech.document.enums.DocumentType;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @EntityGraph(attributePaths = "file")
    Optional<Document> findByIdAndOrganizationIdAndStatusNot(
        UUID id,
        UUID organizationId,
        DocumentStatus status
    );

    @EntityGraph(attributePaths = "file")
    Page<Document> findAllByOrganizationIdAndStatusNot(
        UUID organizationId,
        DocumentStatus status,
        Pageable pageable
    );

    @EntityGraph(attributePaths = "file")
    Page<Document> findAllByOrganizationIdAndDocumentTypeAndStatusNot(
        UUID organizationId,
        DocumentType documentType,
        DocumentStatus status,
        Pageable pageable
    );
}
