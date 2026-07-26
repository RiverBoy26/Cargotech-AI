package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimStatusHistory;

import java.util.List;
import java.util.UUID;

public interface ClaimStatusHistoryRepository extends JpaRepository<ClaimStatusHistory, UUID> {
    List<ClaimStatusHistory> findByClaimIdOrderByChangedAtAsc(UUID claimId);
}
