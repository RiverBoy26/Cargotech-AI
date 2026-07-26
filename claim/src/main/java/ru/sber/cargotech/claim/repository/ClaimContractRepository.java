package ru.sber.cargotech.claim.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimContract;

import java.util.Optional;
import java.util.UUID;

public interface ClaimContractRepository extends JpaRepository<ClaimContract, UUID> {
    Optional<ClaimContract> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);
    Page<ClaimContract> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId, Pageable pageable);
    boolean existsByOrganizationIdAndNumberAndDeletedAtIsNull(UUID organizationId, String number);
}
