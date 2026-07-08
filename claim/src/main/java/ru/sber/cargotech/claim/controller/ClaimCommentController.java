package ru.sber.cargotech.claim.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.claim.dto.ClaimCommentResponse;
import ru.sber.cargotech.claim.dto.CreateCommentRequest;
import ru.sber.cargotech.claim.dto.UpdateCommentRequest;
import ru.sber.cargotech.claim.security.CurrentClaimUserProvider;
import ru.sber.cargotech.claim.service.ClaimCommentService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims/{claimId}/comments")
public class ClaimCommentController {
    private final ClaimCommentService commentService;
    private final CurrentClaimUserProvider currentUserProvider;

    public ClaimCommentController(
        ClaimCommentService commentService,
        CurrentClaimUserProvider currentUserProvider
    ) {
        this.commentService = commentService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public List<ClaimCommentResponse> list(@PathVariable UUID claimId) {
        return commentService.list(currentUserProvider.getRequiredUser(), claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public ClaimCommentResponse create(
        @PathVariable UUID claimId,
        @Valid @RequestBody CreateCommentRequest request
    ) {
        return commentService.create(currentUserProvider.getRequiredUser(), claimId, request);
    }

    @PatchMapping("/{commentId}")
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public ClaimCommentResponse update(
        @PathVariable UUID claimId,
        @PathVariable UUID commentId,
        @Valid @RequestBody UpdateCommentRequest request
    ) {
        return commentService.update(currentUserProvider.getRequiredUser(), claimId, commentId, request);
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CLAIM_READ')")
    public void delete(
        @PathVariable UUID claimId,
        @PathVariable UUID commentId
    ) {
        commentService.delete(currentUserProvider.getRequiredUser(), claimId, commentId);
    }
}
