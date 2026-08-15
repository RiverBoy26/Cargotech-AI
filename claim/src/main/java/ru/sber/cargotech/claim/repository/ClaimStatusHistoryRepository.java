package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimStatusHistory;
import ru.sber.cargotech.claim.enums.ClaimStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, UUID> {
    List<ClaimStatusHistory> findByClaimIdOrderByChangedAtAsc(UUID claimId);

    Optional<ClaimStatusHistory> findTopByClaimIdAndNewStatusOrderByChangedAtDesc(
        UUID claimId,
        ClaimStatus newStatus
    );
}
