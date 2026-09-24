package ru.sber.cargotech.auth.service;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.auth.client.ClaimOrganizationProjectionClient;
import ru.sber.cargotech.auth.client.OrganizationProjectionRequest;
import ru.sber.cargotech.auth.dto.CreateOrganizationRequest;
import ru.sber.cargotech.auth.dto.OrganizationResponse;
import ru.sber.cargotech.auth.dto.PageResponse;
import ru.sber.cargotech.auth.dto.UpdateOrganizationRequest;
import ru.sber.cargotech.auth.entity.AuthOrganization;
import ru.sber.cargotech.auth.enums.OrganizationStatus;
import ru.sber.cargotech.auth.exception.AuthException;
import ru.sber.cargotech.auth.repository.AuthOrganizationRepository;
import ru.sber.cargotech.auth.repository.AuthOutboxWriter;
import ru.sber.cargotech.auth.security.CurrentUser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizationService {

    private final AuthOrganizationRepository organizationRepository;
    private final AuthOutboxWriter outboxWriter;
    private final ClaimOrganizationProjectionClient projectionClient;

    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> findAll(
        String search,
        OrganizationStatus status,
        Pageable pageable
    ) {
        Specification<AuthOrganization> specification =
            (root, query, builder) -> {
                List<Predicate> predicates = new ArrayList<>();
                if (search != null && !search.isBlank()) {
                    String pattern = "%"
                        + search.trim().toLowerCase(Locale.ROOT)
                        + "%";
                    predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), pattern),
                        builder.like(builder.lower(root.get("inn")), pattern)
                    ));
                }
                if (status != null) {
                    predicates.add(builder.equal(root.get("status"), status));
                }
                return builder.and(predicates.toArray(Predicate[]::new));
            };

        Page<OrganizationResponse> result = organizationRepository
            .findAll(specification, pageable)
            .map(this::toResponse);
        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse get(
        UUID organizationId,
        CurrentUser actor
    ) {
        boolean ownOrganization = organizationId.equals(
            actor.organizationId()
        );
        boolean canReadAnyOrganization = actor.permissions().contains(
            "ORGANIZATION_READ"
        );
        if (!ownOrganization && !canReadAnyOrganization) {
            throw AuthException.forbidden(
                "Нет доступа к данным другой организации"
            );
        }
        return toResponse(requireOrganization(organizationId));
    }

    @Transactional
    public OrganizationResponse create(
        CreateOrganizationRequest request,
        CurrentUser actor
    ) {
        String inn = normalizeRequired(request.inn(), "ИНН");
        if (organizationRepository.findByInn(inn).isPresent()) {
            throw AuthException.conflict(
                "Организация с ИНН %s уже существует".formatted(inn)
            );
        }

        AuthOrganization organization = new AuthOrganization();
        organization.setName(normalizeRequired(request.name(), "Название"));
        organization.setInn(inn);
        organization.setKpp(blankToNull(request.kpp()));
        organization.setOgrn(blankToNull(request.ogrn()));
        organization.setLegalAddress(blankToNull(request.legalAddress()));
        organization.setPostalAddress(blankToNull(request.postalAddress()));
        organization.setEmail(normalizeEmail(request.email()));
        organization.setPhone(blankToNull(request.phone()));
        organization.setStatus(
            request.status() == null
                ? OrganizationStatus.ACTIVE
                : request.status()
        );

        organization = organizationRepository.saveAndFlush(organization);
        synchronizeProjection(organization);
        writeEvent(
            organization,
            "AUTH_ORGANIZATION_CREATED",
            actor.userId()
        );
        log.info(
            "Создана организация-экспедитор: organizationId={}, actorUserId={}",
            organization.getId(),
            actor.userId()
        );
        return toResponse(organization);
    }

    @Transactional
    public OrganizationResponse update(
        UUID organizationId,
        UpdateOrganizationRequest request,
        CurrentUser actor
    ) {
        AuthOrganization organization = requireOrganization(organizationId);

        if (request.name() != null) {
            organization.setName(
                normalizeRequired(request.name(), "Название")
            );
        }
        if (request.inn() != null) {
            String inn = normalizeRequired(request.inn(), "ИНН");
            if (organizationRepository.existsByInnAndIdNot(
                inn,
                organizationId
            )) {
                throw AuthException.conflict(
                    "Организация с ИНН %s уже существует".formatted(inn)
                );
            }
            organization.setInn(inn);
        }
        if (request.kpp() != null) {
            organization.setKpp(blankToNull(request.kpp()));
        }
        if (request.ogrn() != null) {
            organization.setOgrn(blankToNull(request.ogrn()));
        }
        if (request.legalAddress() != null) {
            organization.setLegalAddress(
                blankToNull(request.legalAddress())
            );
        }
        if (request.postalAddress() != null) {
            organization.setPostalAddress(
                blankToNull(request.postalAddress())
            );
        }
        if (request.email() != null) {
            organization.setEmail(normalizeEmail(request.email()));
        }
        if (request.phone() != null) {
            organization.setPhone(blankToNull(request.phone()));
        }
        if (request.status() != null) {
            organization.setStatus(request.status());
        }

        organization = organizationRepository.saveAndFlush(organization);
        synchronizeProjection(organization);
        writeEvent(
            organization,
            "AUTH_ORGANIZATION_UPDATED",
            actor.userId()
        );
        return toResponse(organization);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse synchronize(
        UUID organizationId,
        CurrentUser actor
    ) {
        AuthOrganization organization = requireOrganization(organizationId);
        synchronizeProjection(organization);
        log.info(
            "Синхронизирована организация-экспедитор: organizationId={}, actorUserId={}",
            organizationId,
            actor.userId()
        );
        return toResponse(organization);
    }

    private AuthOrganization requireOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId)
            .orElseThrow(() -> AuthException.notFound(
                "Организация %s не найдена".formatted(organizationId)
            ));
    }

    private void synchronizeProjection(AuthOrganization organization) {
        projectionClient.synchronize(new OrganizationProjectionRequest(
            organization.getId(),
            organization.getName(),
            organization.getInn(),
            organization.getKpp(),
            organization.getOgrn(),
            organization.getLegalAddress(),
            organization.getPostalAddress(),
            organization.getEmail(),
            organization.getPhone(),
            organization.getStatus() == OrganizationStatus.ACTIVE
        ));
    }

    private void writeEvent(
        AuthOrganization organization,
        String eventType,
        UUID actorUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", organization.getName());
        payload.put("inn", organization.getInn());
        payload.put("status", organization.getStatus().name());
        outboxWriter.write(
            "ORGANIZATION",
            organization.getId(),
            eventType,
            organization.getId(),
            actorUserId,
            payload
        );
    }

    private OrganizationResponse toResponse(AuthOrganization organization) {
        return new OrganizationResponse(
            organization.getId(),
            organization.getName(),
            organization.getInn(),
            organization.getKpp(),
            organization.getOgrn(),
            organization.getLegalAddress(),
            organization.getPostalAddress(),
            organization.getEmail(),
            organization.getPhone(),
            organization.getStatus(),
            organization.getCreatedAt(),
            organization.getUpdatedAt()
        );
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw AuthException.validation(field + " не может быть пустым");
        }
        return normalized;
    }

    private static String normalizeEmail(String value) {
        String email = blankToNull(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
