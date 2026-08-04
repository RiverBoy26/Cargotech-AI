package ru.sber.cargotech.document.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.sber.cargotech.document.entity.ClaimTemplateVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimTemplateVersionRepository extends JpaRepository<ClaimTemplateVersion, UUID> {

    List<ClaimTemplateVersion> findAllByTemplate_IdOrderByVersionNumberDesc(UUID templateId);

    Optional<ClaimTemplateVersion> findByIdAndTemplate_Id(UUID id, UUID templateId);

    Optional<ClaimTemplateVersion> findByIdAndTemplate_IdAndActiveTrue(
        UUID id,
        UUID templateId
    );

    Optional<ClaimTemplateVersion> findFirstByTemplate_IdAndActiveTrue(UUID templateId);

    @Query("select coalesce(max(v.versionNumber), 0) from ClaimTemplateVersion v where v.template.id = :templateId")
    int maxVersionNumber(UUID templateId);

    @Modifying
    @Query("update ClaimTemplateVersion v set v.active = false where v.template.id = :templateId")
    void deactivateAll(UUID templateId);
}
