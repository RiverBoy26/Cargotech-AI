package ru.sber.cargotech.document.service;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.document.crypto.DocumentTemplateCryptoService;
import ru.sber.cargotech.document.dto.ClaimTemplateResponse;
import ru.sber.cargotech.document.dto.ClaimTemplateOptionResponse;
import ru.sber.cargotech.document.dto.ClaimTemplateVersionResponse;
import ru.sber.cargotech.document.dto.CreateClaimTemplateRequest;
import ru.sber.cargotech.document.dto.CreateClaimTemplateVersionRequest;
import ru.sber.cargotech.document.dto.PageResponse;
import ru.sber.cargotech.document.dto.TemplatePreviewRequest;
import ru.sber.cargotech.document.dto.TemplatePreviewResponse;
import ru.sber.cargotech.document.entity.ClaimTemplate;
import ru.sber.cargotech.document.entity.ClaimTemplateVersion;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.repository.ClaimTemplateRepository;
import ru.sber.cargotech.document.repository.ClaimTemplateVersionRepository;
import ru.sber.cargotech.document.security.CurrentDocumentUser;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ClaimTemplateService {

    private final ClaimTemplateRepository templateRepository;
    private final ClaimTemplateVersionRepository versionRepository;
    private final DocumentTemplateCryptoService cryptoService;
    private final TemplateVariableExtractor variableExtractor;
    private final TemplateTextRenderer textRenderer;
    private final DocumentMapper mapper;

    public ClaimTemplateService(
        ClaimTemplateRepository templateRepository,
        ClaimTemplateVersionRepository versionRepository,
        DocumentTemplateCryptoService cryptoService,
        TemplateVariableExtractor variableExtractor,
        TemplateTextRenderer textRenderer,
        DocumentMapper mapper
    ) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
        this.cryptoService = cryptoService;
        this.variableExtractor = variableExtractor;
        this.textRenderer = textRenderer;
        this.mapper = mapper;
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    @Transactional(readOnly = true)
    public PageResponse<ClaimTemplateResponse> findAll(
        Pageable pageable,
        CurrentDocumentUser user
    ) {
        return PageResponse.from(templateRepository
            .findAllAvailableToOrganization(user.organizationId(), pageable)
            .map(template -> mapper.toTemplateResponse(
                template,
                versionRepository.findAllByTemplate_IdOrderByVersionNumberDesc(template.getId())
            ))
        );
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    @Transactional(readOnly = true)
    public ClaimTemplateResponse get(
        UUID templateId,
        CurrentDocumentUser user
    ) {
        ClaimTemplate template = getTemplate(templateId, user.organizationId());
        return mapper.toTemplateResponse(
            template,
            versionRepository.findAllByTemplate_IdOrderByVersionNumberDesc(template.getId())
        );
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_CREATE')")
    @Transactional
    public ClaimTemplateResponse create(
        CreateClaimTemplateRequest request,
        CurrentDocumentUser user
    ) {
        String code = normalizeCode(request.code());

        if (templateRepository.existsByOrganizationIdIsNullAndCode(code)) {
            throw DocumentException.conflict(
                "Шаблон с кодом %s уже существует".formatted(code)
            );
        }

        ClaimTemplate template = new ClaimTemplate();
        // Claim templates are system-wide. A null organization_id makes the
        // template available to every organization while tenant-owned claims
        // and generated documents remain isolated by organization.
        template.setOrganizationId(null);
        template.setCode(code);
        template.setName(request.name());
        template.setClaimType(request.claimType());
        template.setClientId(request.clientId());
        template.setDescription(request.description());
        template.setDefaultTemplate(Boolean.TRUE.equals(request.defaultTemplate()));
        template.setPriority(request.priority() == null ? 0 : request.priority());
        template.setCreatedBy(user.userId());
        template = templateRepository.save(template);

        return mapper.toTemplateResponse(template, List.of());
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_UPDATE')")
    @Transactional
    public ClaimTemplateVersionResponse createVersion(
        UUID templateId,
        CreateClaimTemplateVersionRequest request,
        CurrentDocumentUser user
    ) {
        ClaimTemplate template = getGlobalTemplate(templateId);
        String content = request.content();
        int versionNumber = versionRepository.maxVersionNumber(template.getId()) + 1;

        ClaimTemplateVersion version = new ClaimTemplateVersion();
        version.setTemplate(template);
        version.setVersionNumber(versionNumber);
        version.setEncryptedContent(cryptoService.encrypt(content));
        version.setContentSha256(cryptoService.sha256(content));
        version.setEncryptionKeyId(cryptoService.keyId());
        version.setEncryptionAlgorithm("AES-256-GCM");
        version.setContentFormat("MUSTACHE_TEXT");
        version.setVariables(variableExtractor.extractVariables(content));
        version.setChangeComment(request.changeComment());
        version.setCreatedBy(user.userId());
        version.setActive(false);
        version = versionRepository.save(version);

        if (request.activate()) {
            return mapper.toTemplateVersionResponse(activateVersion(
                template.getId(),
                version.getId(),
                user
            ));
        }

        return mapper.toTemplateVersionResponse(version);
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_UPDATE')")
    @Transactional
    public ClaimTemplateVersionResponse activate(
        UUID templateId,
        UUID versionId,
        CurrentDocumentUser user
    ) {
        return mapper.toTemplateVersionResponse(
            activateVersion(templateId, versionId, user)
        );
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    @Transactional(readOnly = true)
    public List<ClaimTemplateOptionResponse> findAvailable(
        String claimType,
        UUID clientId,
        CurrentDocumentUser user
    ) {
        if (claimType == null || claimType.isBlank()) {
            throw DocumentException.badRequest("claimType обязателен");
        }

        List<TemplateWithVersion> candidates = templateRepository
            .findAvailableForClaim(
                user.organizationId(),
                claimType.trim().toUpperCase(),
                clientId
            )
            .stream()
            .map(template -> versionRepository
                .findFirstByTemplate_IdAndActiveTrue(template.getId())
                .map(version -> new TemplateWithVersion(template, version))
                .orElse(null)
            )
            .filter(java.util.Objects::nonNull)
            .sorted(templateComparator(user.organizationId(), clientId))
            .toList();

        UUID recommendedId = candidates.isEmpty()
            ? null
            : candidates.getFirst().template().getId();

        return candidates.stream()
            .map(candidate -> new ClaimTemplateOptionResponse(
                candidate.template().getId(),
                candidate.version().getId(),
                candidate.template().getCode(),
                candidate.template().getName(),
                candidate.template().getClaimType(),
                candidate.template().getClientId(),
                candidate.template().getClientId() != null,
                candidate.template().isDefaultTemplate(),
                candidate.template().getPriority(),
                candidate.template().getId().equals(recommendedId)
            ))
            .toList();
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    @Transactional(readOnly = true)
    public TemplatePreviewResponse preview(
        UUID templateId,
        TemplatePreviewRequest request,
        CurrentDocumentUser user
    ) {
        ClaimTemplate template = getTemplate(templateId, user.organizationId());
        ClaimTemplateVersion version = resolvePreviewVersion(
            template,
            request == null ? null : request.templateVersionId()
        );
        String content = cryptoService.decrypt(version.getEncryptedContent());
        TemplateTextRenderer.RenderedText rendered = textRenderer.render(
            content,
            request == null ? null : request.data(),
            true
        );

        return new TemplatePreviewResponse(
            template.getId(),
            version.getId(),
            template.getCode(),
            template.getName(),
            template.getClaimType(),
            rendered.content(),
            rendered.variables(),
            rendered.missingVariables()
        );
    }

    @PreAuthorize("hasAuthority('DOCUMENT_TEMPLATE_READ')")
    @Transactional(readOnly = true)
    public ExportedTemplate export(
        UUID templateId,
        UUID versionId,
        CurrentDocumentUser user
    ) {
        ClaimTemplate template = getTemplate(templateId, user.organizationId());
        ClaimTemplateVersion version = versionRepository
            .findByIdAndTemplate_Id(versionId, template.getId())
            .orElseThrow(() -> DocumentException.notFound(
                "Версия шаблона %s не найдена".formatted(versionId)
            ));

        return new ExportedTemplate(
            template.getCode().toLowerCase() + "_v"
                + version.getVersionNumber() + ".txt",
            cryptoService.decrypt(version.getEncryptedContent())
        );
    }

    @Transactional(readOnly = true)
    public ResolvedTemplate resolveActiveTemplate(
        UUID organizationId,
        UUID templateId,
        UUID templateVersionId,
        String templateCode
    ) {
        ClaimTemplate template;

        if (templateId != null) {
            template = getTemplate(templateId, organizationId);
        } else if (templateCode != null && !templateCode.isBlank()) {
            template = templateRepository
                .findAvailableByCode(
                    organizationId,
                    normalizeCode(templateCode)
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> DocumentException.notFound(
                    "Шаблон с кодом %s не найден".formatted(templateCode)
                ));
        } else {
            throw DocumentException.badRequest(
                "Нужно передать templateId или templateCode"
            );
        }

        ClaimTemplateVersion version = versionRepository
            .findFirstByTemplate_IdAndActiveTrue(template.getId())
            .orElseThrow(() -> DocumentException.conflict(
                "У шаблона %s нет активной версии".formatted(template.getCode())
            ));

        if (templateVersionId != null && !templateVersionId.equals(version.getId())) {
            throw DocumentException.conflict(
                "Активная версия шаблона изменилась; обновите предпросмотр"
            );
        }

        return new ResolvedTemplate(
            template,
            version,
            cryptoService.decrypt(version.getEncryptedContent())
        );
    }

    private ClaimTemplateVersion activateVersion(
        UUID templateId,
        UUID versionId,
        CurrentDocumentUser user
    ) {
        ClaimTemplate template = getGlobalTemplate(templateId);
        ClaimTemplateVersion version = versionRepository
            .findByIdAndTemplate_Id(versionId, template.getId())
            .orElseThrow(() -> DocumentException.notFound(
                "Версия шаблона %s не найдена".formatted(versionId)
            ));

        versionRepository.deactivateAll(template.getId());
        version.setActive(true);
        return versionRepository.save(version);
    }

    private ClaimTemplate getTemplate(UUID templateId, UUID organizationId) {
        return templateRepository
            .findAvailableById(templateId, organizationId)
            .orElseThrow(() -> DocumentException.notFound(
                "Шаблон %s не найден".formatted(templateId)
            ));
    }

    private ClaimTemplate getGlobalTemplate(UUID templateId) {
        return templateRepository
            .findByIdAndOrganizationIdIsNullAndActiveTrue(templateId)
            .orElseThrow(() -> DocumentException.notFound(
                "Глобальный шаблон %s не найден".formatted(templateId)
            ));
    }

    private ClaimTemplateVersion resolvePreviewVersion(
        ClaimTemplate template,
        UUID requestedVersionId
    ) {
        if (requestedVersionId == null) {
            return versionRepository
                .findFirstByTemplate_IdAndActiveTrue(template.getId())
                .orElseThrow(() -> DocumentException.conflict(
                    "У шаблона %s нет активной версии".formatted(template.getCode())
                ));
        }

        return versionRepository
            .findByIdAndTemplate_IdAndActiveTrue(
                requestedVersionId,
                template.getId()
            )
            .orElseThrow(() -> DocumentException.conflict(
                "Выбранная версия шаблона больше не активна; обновите предпросмотр"
            ));
    }

    private Comparator<TemplateWithVersion> templateComparator(
        UUID organizationId,
        UUID clientId
    ) {
        return Comparator
            .comparingInt((TemplateWithVersion item) ->
                item.template().getClientId() != null
                    && item.template().getClientId().equals(clientId) ? 0 : 1
            )
            .thenComparingInt(item ->
                item.template().getOrganizationId() != null
                    && item.template().getOrganizationId().equals(organizationId) ? 0 : 1
            )
            .thenComparing(
                item -> item.template().isDefaultTemplate(),
                Comparator.reverseOrder()
            )
            .thenComparing(
                item -> item.template().getPriority(),
                Comparator.reverseOrder()
            )
            .thenComparing(item -> item.template().getName());
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase();
    }

    public record ResolvedTemplate(
        ClaimTemplate template,
        ClaimTemplateVersion version,
        String decryptedContent
    ) {
    }

    private record TemplateWithVersion(
        ClaimTemplate template,
        ClaimTemplateVersion version
    ) {
    }

    public record ExportedTemplate(
        String filename,
        String content
    ) {
    }
}
