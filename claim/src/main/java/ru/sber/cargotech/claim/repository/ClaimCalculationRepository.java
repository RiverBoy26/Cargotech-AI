package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sber.cargotech.claim.entity.ClaimCalculation;

import java.util.Optional;
import java.util.UUID;

public interface ClaimCalculationRepository extends JpaRepository<ClaimCalculation, UUID> {
    Optional<ClaimCalculation> findFirstByClaimIdOrderByCalculationVersionDesc(UUID claimId);

    @Query("select coalesce(max(c.calculationVersion), 0) from ClaimCalculation c where c.claimId = :claimId")
    int findLastCalculationVersion(@Param("claimId") UUID claimId);
}
