package ru.sber.cargotech.claim.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.claim.entity.ClaimComment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimCommentRepository extends JpaRepository<ClaimComment, UUID> {
    List<ClaimComment> findByClaimIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID claimId);
    Optional<ClaimComment> findByIdAndClaimIdAndDeletedAtIsNull(UUID id, UUID claimId);
}
