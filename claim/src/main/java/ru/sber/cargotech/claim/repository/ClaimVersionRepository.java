package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sber.cargotech.claim.entity.ClaimVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimVersionRepository extends JpaRepository<ClaimVersion, UUID> {
    List<ClaimVersion> findByClaimIdOrderByVersionNumberDesc(UUID claimId);
    Optional<ClaimVersion> findByIdAndClaimId(UUID id, UUID claimId);

    @Query("select coalesce(max(v.versionNumber), 0) from ClaimVersion v where v.claimId = :claimId")
    int findLastVersionNumber(@Param("claimId") UUID claimId);

    @Modifying
    @Query("update ClaimVersion v set v.finalVersion = false where v.claimId = :claimId")
    void clearFinalFlags(@Param("claimId") UUID claimId);
}
