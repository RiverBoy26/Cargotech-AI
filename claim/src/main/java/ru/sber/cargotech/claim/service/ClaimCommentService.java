package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ClaimCommentResponse;
import ru.sber.cargotech.claim.dto.CreateCommentRequest;
import ru.sber.cargotech.claim.dto.UpdateCommentRequest;
import ru.sber.cargotech.claim.entity.ClaimComment;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimCommentRepository;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaimCommentService {
    private final ClaimRepository claimRepository;
    private final ClaimCommentRepository commentRepository;
    private final ClaimOutboxWriter outboxWriter;

    @Transactional(readOnly = true)
    public List<ClaimCommentResponse> list(CurrentClaimUser user, UUID claimId) {
        log.debug("Получение комментариев: claimId={}, organizationId={}", claimId, user.organizationId());

        ClaimEntity claim = getClaim(user, claimId);
        return commentRepository.findByClaimIdAndDeletedAtIsNullOrderByCreatedAtAsc(claim.getId())
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public ClaimCommentResponse create(CurrentClaimUser user, UUID claimId, CreateCommentRequest request) {
        log.debug("Создание комментария: claimId={}, userId={}, textLength={}", claimId, user.userId(), request.text() == null ? 0 : request.text().length());

        ClaimEntity claim = getClaim(user, claimId);
        ClaimComment comment = new ClaimComment();
        comment.setClaimId(claim.getId());
        comment.setAuthorId(user.userId());
        comment.setText(request.text());
        ClaimComment saved = commentRepository.save(comment);
        outboxWriter.write(
            "CLAIM",
            claim.getId(),
            "CLAIM_COMMENT_ADDED",
            user.organizationId(),
            user.userId(),
            Map.of("claimId", claim.getId(), "commentId", saved.getId())
        );
        return toResponse(saved);
    }

    @Transactional
    public ClaimCommentResponse update(
        CurrentClaimUser user,
        UUID claimId,
        UUID commentId,
        UpdateCommentRequest request
    ) {
        log.debug("Обновление комментария: claimId={}, commentId={}, userId={}, textLength={}", claimId, commentId, user.userId(), request.text() == null ? 0 : request.text().length());

        ClaimEntity claim = getClaim(user, claimId);
        ClaimComment comment = getComment(claim.getId(), commentId);
        ensureAuthor(user, comment);
        comment.setText(request.text());
        ClaimComment saved = commentRepository.save(comment);
        outboxWriter.write(
            "CLAIM",
            claim.getId(),
            "CLAIM_COMMENT_UPDATED",
            user.organizationId(),
            user.userId(),
            Map.of("claimId", claim.getId(), "commentId", saved.getId())
        );
        return toResponse(saved);
    }

    @Transactional
    public void delete(CurrentClaimUser user, UUID claimId, UUID commentId) {
        log.debug("Удаление комментария: claimId={}, commentId={}, userId={}", claimId, commentId, user.userId());

        ClaimEntity claim = getClaim(user, claimId);
        ClaimComment comment = getComment(claim.getId(), commentId);
        ensureAuthor(user, comment);
        comment.setDeletedAt(OffsetDateTime.now());
        commentRepository.save(comment);
        outboxWriter.write(
            "CLAIM",
            claim.getId(),
            "CLAIM_COMMENT_DELETED",
            user.organizationId(),
            user.userId(),
            Map.of("claimId", claim.getId(), "commentId", comment.getId())
        );
    }

    private ClaimEntity getClaim(CurrentClaimUser user, UUID claimId) {
        return claimRepository.findByIdAndOrganizationId(claimId, user.organizationId())
            .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));
    }

    private ClaimComment getComment(UUID claimId, UUID commentId) {
        return commentRepository.findByIdAndClaimIdAndDeletedAtIsNull(commentId, claimId)
            .orElseThrow(() -> ClaimException.notFound("Комментарий не найден"));
    }

    private void ensureAuthor(CurrentClaimUser user, ClaimComment comment) {
        if (!comment.getAuthorId().equals(user.userId())) {
            throw ClaimException.forbidden("Редактировать или удалять можно только свой комментарий");
        }
    }

    private ClaimCommentResponse toResponse(ClaimComment comment) {
        return new ClaimCommentResponse(
            comment.getId(),
            comment.getClaimId(),
            comment.getAuthorId(),
            comment.getText(),
            comment.getCreatedAt(),
            comment.getUpdatedAt()
        );
    }
}
