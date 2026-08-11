package ru.sber.cargotech.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.document.entity.DocumentLink;
import ru.sber.cargotech.document.enums.DocumentEntityType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentLinkRepository extends JpaRepository<DocumentLink, UUID> {

    List<DocumentLink> findAllByEntityTypeAndEntityIdOrderByCreatedAtDesc(
        DocumentEntityType entityType,
        UUID entityId
    );

    List<DocumentLink> findAllByDocument_Id(UUID documentId);

    Optional<DocumentLink> findByIdAndDocument_Id(UUID id, UUID documentId);

    boolean existsByDocument_IdAndEntityTypeAndEntityIdAndLinkType(
        UUID documentId,
        DocumentEntityType entityType,
        UUID entityId,
        String linkType
    );
}
