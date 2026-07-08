package ru.sber.cargotech.claim.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ClaimVersionResponse;
import ru.sber.cargotech.claim.dto.CreateClaimVersionRequest;
import ru.sber.cargotech.claim.dto.VersionDiffResponse;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimVersion;
import ru.sber.cargotech.claim.enums.ClaimVersionSource;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.repository.ClaimVersionRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ClaimVersionService {
    private final ClaimRepository claimRepository;
    private final ClaimVersionRepository versionRepository;
    private final ClaimOutboxWriter outboxWriter;

    public ClaimVersionService(
        ClaimRepository claimRepository,
        ClaimVersionRepository versionRepository,
        ClaimOutboxWriter outboxWriter
    ) {
        this.claimRepository = claimRepository;
        this.versionRepository = versionRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public List<ClaimVersionResponse> list(CurrentClaimUser user, UUID claimId) {
        ClaimEntity claim = getClaim(user, claimId);
        return versionRepository.findByClaimIdOrderByVersionNumberDesc(claim.getId())
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public ClaimVersionResponse get(CurrentClaimUser user, UUID claimId, UUID versionId) {
        ClaimEntity claim = getClaim(user, claimId);
        return toResponse(getVersion(claim.getId(), versionId));
    }

    @Transactional
    public ClaimVersionResponse create(
        CurrentClaimUser user,
        UUID claimId,
        CreateClaimVersionRequest request
    ) {
        ClaimEntity claim = getClaim(user, claimId);
        ClaimVersion version = new ClaimVersion();
        version.setClaimId(claim.getId());
        version.setVersionNumber(versionRepository.findLastVersionNumber(claim.getId()) + 1);
        version.setSource(request.source() == null ? ClaimVersionSource.LAWYER : request.source());
        version.setBaseVersionId(request.baseVersionId());
        version.setContent(request.content());
        version.setComment(request.comment());
        version.setCreatedBy(user.userId());
        version.setFinalVersion(Boolean.TRUE.equals(request.finalVersion()));

        if (version.isFinalVersion()) {
            versionRepository.clearFinalFlags(claim.getId());
        }
        ClaimVersion saved = versionRepository.save(version);
        if (saved.isFinalVersion()) {
            claim.setFinalVersionId(saved.getId());
            claim.setUpdatedBy(user.userId());
            claimRepository.save(claim);
        }

        outboxWriter.write(
            "CLAIM",
            claim.getId(),
            "CLAIM_VERSION_CREATED",
            user.organizationId(),
            user.userId(),
            Map.of("claimId", claim.getId(), "versionId", saved.getId())
        );
        return toResponse(saved);
    }

    @Transactional
    public ClaimVersionResponse markFinal(CurrentClaimUser user, UUID claimId, UUID versionId) {
        ClaimEntity claim = getClaim(user, claimId);
        ClaimVersion version = getVersion(claim.getId(), versionId);
        versionRepository.clearFinalFlags(claim.getId());
        version.setFinalVersion(true);
        ClaimVersion saved = versionRepository.save(version);
        claim.setFinalVersionId(saved.getId());
        claim.setUpdatedBy(user.userId());
        claimRepository.save(claim);
        outboxWriter.write(
            "CLAIM",
            claim.getId(),
            "CLAIM_VERSION_MARKED_FINAL",
            user.organizationId(),
            user.userId(),
            Map.of("claimId", claim.getId(), "versionId", saved.getId())
        );
        return toResponse(saved);
    }

    @Transactional
    public ClaimVersionResponse restore(CurrentClaimUser user, UUID claimId, UUID versionId) {
        ClaimEntity claim = getClaim(user, claimId);
        ClaimVersion source = getVersion(claim.getId(), versionId);
        return create(
            user,
            claim.getId(),
            new CreateClaimVersionRequest(
                ClaimVersionSource.RESTORED,
                source.getId(),
                source.getContent(),
                "Восстановлена версия " + source.getVersionNumber(),
                true
            )
        );
    }

    @Transactional(readOnly = true)
    public VersionDiffResponse diff(CurrentClaimUser user, UUID claimId, UUID versionId) {
        ClaimEntity claim = getClaim(user, claimId);
        ClaimVersion version = getVersion(claim.getId(), versionId);
        ClaimVersion base = resolveBaseVersion(version);
        LinkedHashSet<String> currentLines = new LinkedHashSet<>(Arrays.asList(version.getContent().split("\\R")));
        LinkedHashSet<String> baseLines = new LinkedHashSet<>(Arrays.asList(base.getContent().split("\\R")));
        List<String> added = currentLines.stream()
            .filter(line -> !baseLines.contains(line))
            .toList();
        List<String> removed = baseLines.stream()
            .filter(line -> !currentLines.contains(line))
            .toList();
        return new VersionDiffResponse(base.getId(), version.getId(), added, removed);
    }

    private ClaimVersion resolveBaseVersion(ClaimVersion version) {
        if (version.getBaseVersionId() != null) {
            return versionRepository.findByIdAndClaimId(version.getBaseVersionId(), version.getClaimId())
                .orElseThrow(() -> ClaimException.notFound("Базовая версия претензии не найдена"));
        }
        if (version.getVersionNumber() <= 1) {
            return version;
        }
        return versionRepository.findByClaimIdOrderByVersionNumberDesc(version.getClaimId())
            .stream()
            .filter(candidate -> candidate.getVersionNumber() == version.getVersionNumber() - 1)
            .findFirst()
            .orElse(version);
    }

    private ClaimEntity getClaim(CurrentClaimUser user, UUID claimId) {
        return claimRepository.findByIdAndOrganizationId(claimId, user.organizationId())
            .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));
    }

    private ClaimVersion getVersion(UUID claimId, UUID versionId) {
        return versionRepository.findByIdAndClaimId(versionId, claimId)
            .orElseThrow(() -> ClaimException.notFound("Версия претензии не найдена"));
    }

    private ClaimVersionResponse toResponse(ClaimVersion version) {
        return new ClaimVersionResponse(
            version.getId(),
            version.getClaimId(),
            version.getVersionNumber(),
            version.getSource(),
            version.getBaseVersionId(),
            version.getContent(),
            version.getComment(),
            version.isFinalVersion(),
            version.getCreatedBy(),
            version.getCreatedAt()
        );
    }
}
