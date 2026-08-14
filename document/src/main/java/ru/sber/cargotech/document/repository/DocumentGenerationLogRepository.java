package ru.sber.cargotech.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.document.entity.DocumentGenerationLog;
import ru.sber.cargotech.document.enums.GeneratedDocumentType;
import ru.sber.cargotech.document.enums.GenerationStatus;

import java.util.UUID;

public interface DocumentGenerationLogRepository extends JpaRepository<DocumentGenerationLog, UUID> {
    boolean existsByOrganizationIdAndClaimIdAndClaimVersionIdAndOutputTypeAndStatusIn(
        UUID organizationId,
        UUID claimId,
        UUID claimVersionId,
        GeneratedDocumentType outputType,
        java.util.Collection<GenerationStatus> statuses
    );
}
