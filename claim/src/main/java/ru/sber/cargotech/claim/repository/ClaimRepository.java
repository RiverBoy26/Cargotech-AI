package ru.sber.cargotech.claim.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.enums.ClaimStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<ClaimEntity, UUID> {
    boolean existsByOrganizationIdAndShipmentIdAndIdNot(
        UUID organizationId,
        UUID shipmentId,
        UUID id
    );
    Optional<ClaimEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select c
        from ClaimEntity c
        where c.id = :id
          and c.organizationId = :organizationId
        """)
    Optional<ClaimEntity> findByIdAndOrganizationIdForUpdate(
        @Param("id") UUID id,
        @Param("organizationId") UUID organizationId
    );
    boolean existsByOrganizationIdAndClaimNumber(UUID organizationId, String claimNumber);
    boolean existsByOrganizationIdAndShipmentIdAndStatusNotIn(UUID organizationId, UUID shipmentId, Collection<ClaimStatus> statuses);
    Optional<ClaimEntity> findFirstByOrganizationIdAndShipmentIdAndStatusNotIn(UUID organizationId, UUID shipmentId, Collection<ClaimStatus> statuses);
    Optional<ClaimEntity> findFirstByOrganizationIdAndShipmentIdOrderByCreatedAtDesc(UUID organizationId, UUID shipmentId);
    List<ClaimEntity> findAllByOrganizationId(UUID organizationId);
    List<ClaimEntity> findAllByStatusNotIn(Collection<ClaimStatus> statuses);
}
