package ru.sber.cargotech.claim.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ShipmentStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimShipmentRepository extends JpaRepository<ClaimShipment, UUID> {
    Optional<ClaimShipment> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Page<ClaimShipment> findByOrganizationId(UUID organizationId, Pageable pageable);
    List<ClaimShipment> findByOrganizationId(UUID organizationId);
    List<ClaimShipment> findAllByStatusNot(ShipmentStatus status);
}
