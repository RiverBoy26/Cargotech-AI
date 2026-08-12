package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.enums.ClaimStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<ClaimEntity, UUID> {
    Optional<ClaimEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    boolean existsByOrganizationIdAndClaimNumber(UUID organizationId, String claimNumber);
    boolean existsByOrganizationIdAndShipmentIdAndStatusNotIn(UUID organizationId, UUID shipmentId, Collection<ClaimStatus> statuses);
    Optional<ClaimEntity> findFirstByOrganizationIdAndShipmentIdAndStatusNotIn(UUID organizationId, UUID shipmentId, Collection<ClaimStatus> statuses);
    Optional<ClaimEntity> findFirstByOrganizationIdAndShipmentIdOrderByCreatedAtDesc(UUID organizationId, UUID shipmentId);
    List<ClaimEntity> findAllByOrganizationId(UUID organizationId);
    List<ClaimEntity> findAllByStatusNotIn(Collection<ClaimStatus> statuses);
}
