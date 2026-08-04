package ru.sber.cargotech.claim.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PartyType;

import java.util.Optional;
import java.util.UUID;

public interface ClaimPartyRepository extends JpaRepository<ClaimParty, UUID> {
    Optional<ClaimParty> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);
    Page<ClaimParty> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId, Pageable pageable);
    Optional<ClaimParty> findByIdAndOrganizationId(
            UUID id,
            UUID organizationId
    );
    Page<ClaimParty> findByOrganizationIdAndTypeAndDeletedAtIsNull(
            UUID organizationId,
            PartyType type,
            Pageable pageable
    );
}
