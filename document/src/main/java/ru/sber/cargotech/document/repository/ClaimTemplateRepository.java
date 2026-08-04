package ru.sber.cargotech.document.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.sber.cargotech.document.entity.ClaimTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimTemplateRepository extends JpaRepository<ClaimTemplate, UUID> {

    @Query("""
        select t from ClaimTemplate t
        where t.active = true
          and (t.organizationId = :organizationId or t.organizationId is null)
        order by t.defaultTemplate desc, t.priority desc, t.name asc
        """)
    Page<ClaimTemplate> findAllAvailableToOrganization(
        UUID organizationId,
        Pageable pageable
    );

    @Query("""
        select t from ClaimTemplate t
        where t.id = :id
          and t.active = true
          and (t.organizationId = :organizationId or t.organizationId is null)
        """)
    Optional<ClaimTemplate> findAvailableById(
        UUID id,
        UUID organizationId
    );

    Optional<ClaimTemplate> findByIdAndOrganizationIdIsNullAndActiveTrue(UUID id);

    @Query("""
        select t from ClaimTemplate t
        where t.code = :code
          and t.active = true
          and (t.organizationId = :organizationId or t.organizationId is null)
        order by case when t.organizationId = :organizationId then 0 else 1 end
        """)
    List<ClaimTemplate> findAvailableByCode(
        UUID organizationId,
        String code
    );

    @Query("""
        select t from ClaimTemplate t
        where t.active = true
          and (t.organizationId = :organizationId or t.organizationId is null)
          and t.claimType = :claimType
          and (t.clientId is null or t.clientId = :clientId)
        """)
    List<ClaimTemplate> findAvailableForClaim(
        UUID organizationId,
        String claimType,
        UUID clientId
    );

    boolean existsByOrganizationIdIsNullAndCode(String code);
}
