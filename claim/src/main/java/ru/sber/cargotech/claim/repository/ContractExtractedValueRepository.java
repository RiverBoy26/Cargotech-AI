package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ContractExtractedValue;

import java.util.List;
import java.util.UUID;

public interface ContractExtractedValueRepository extends JpaRepository<ContractExtractedValue, UUID> {
    List<ContractExtractedValue> findByContractIdOrderByCreatedAtAsc(UUID contractId);
    void deleteByContractId(UUID contractId);
}
