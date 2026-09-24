package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimContractClause;

import java.util.List;
import java.util.UUID;

public interface ClaimContractClauseRepository extends JpaRepository<ClaimContractClause, UUID> {
    void deleteByContractIdAndExtractedTrue(UUID contractId);

    List<ClaimContractClause> findAllByContractIdAndActiveTrueOrderByClauseNumberAsc(UUID contractId);
}
