package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.PartyRequest;
import ru.sber.cargotech.claim.dto.PartyResponse;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.enums.PartyType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimPartyRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PartyService {
    private final ClaimPartyRepository partyRepository;
    private final ClaimOutboxWriter outboxWriter;

    @Transactional(readOnly = true)
    public Page<PartyResponse> list(
            CurrentClaimUser user,
            PartyType type,
            Pageable pageable
    ) {
        log.debug(
                "Получение контрагентов: organizationId={}, type={}, page={}, size={}",
                user.organizationId(),
                type,
                pageable.getPageNumber(),
                pageable.getPageSize()
        );

        Page<ClaimParty> parties;

        if (type == null) {
            parties = partyRepository.findByOrganizationIdAndDeletedAtIsNull(
                    user.organizationId(),
                    pageable
            );
        } else {
            parties =
                    partyRepository.findByOrganizationIdAndTypeAndDeletedAtIsNull(
                            user.organizationId(),
                            type,
                            pageable
                    );
        }
        log.debug(
                "Контрагенты получены: organizationId={}, type={}, totalElements={}, totalPages={}",
                user.organizationId(),
                type,
                parties.getTotalElements(),
                parties.getTotalPages()
        );

        return parties.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PartyResponse get(CurrentClaimUser user, UUID id) {
        log.debug("Получение контрагента: partyId={}, organizationId={}", id, user.organizationId());

        return toResponse(getEntity(user.organizationId(), id));
    }

    @Transactional
    public PartyResponse create(CurrentClaimUser user, PartyRequest request) {
        log.debug("Создание контрагента: organizationId={}, userId={}, type={}, inn={}", user.organizationId(), user.userId(), request.type(), request.inn());

        ensureNotManagedExpeditor(request.type());
        ClaimParty party = new ClaimParty();
        party.setOrganizationId(user.organizationId());
        party.setCreatedBy(user.userId());
        party.setUpdatedBy(user.userId());
        apply(party, request, user.userId());
        ClaimParty saved = partyRepository.save(party);
        outboxWriter.write(
            "PARTY",
            saved.getId(),
            "PARTY_CREATED",
            user.organizationId(),
            user.userId(),
            Map.of("partyId", saved.getId())
        );
        return toResponse(saved);
    }

    @Transactional
    public PartyResponse update(CurrentClaimUser user, UUID id, PartyRequest request) {
        log.debug("Обновление контрагента: partyId={}, organizationId={}, userId={}, type={}, inn={}", id, user.organizationId(), user.userId(), request.type(), request.inn());

        ClaimParty party = getEntity(user.organizationId(), id);
        ensureEditableParty(party);
        ensureNotManagedExpeditor(request.type());
        apply(party, request, user.userId());
        ClaimParty saved = partyRepository.save(party);
        outboxWriter.write(
            "PARTY",
            saved.getId(),
            "PARTY_UPDATED",
            user.organizationId(),
            user.userId(),
            Map.of("partyId", saved.getId())
        );
        return toResponse(saved);
    }

    @Transactional
    public void delete(CurrentClaimUser user, UUID id) {
        log.debug("Удаление контрагента: partyId={}, organizationId={}, userId={}", id, user.organizationId(), user.userId());

        ClaimParty party = getEntity(user.organizationId(), id);
        ensureEditableParty(party);
        party.setActive(false);
        party.setDeletedAt(OffsetDateTime.now());
        party.setUpdatedBy(user.userId());
        partyRepository.save(party);
        outboxWriter.write(
            "PARTY",
            party.getId(),
            "PARTY_DELETED",
            user.organizationId(),
            user.userId(),
            Map.of("partyId", party.getId())
        );
    }

    public ClaimParty getEntity(UUID organizationId, UUID id) {
        return partyRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(id, organizationId)
            .orElseThrow(() -> ClaimException.notFound("Контрагент не найден"));
    }

    private void apply(ClaimParty party, PartyRequest request, UUID userId) {
        party.setType(request.type());
        party.setName(request.name());
        party.setInn(blankToNull(request.inn()));
        party.setKpp(blankToNull(request.kpp()));
        party.setOgrn(blankToNull(request.ogrn()));
        party.setLegalAddress(blankToNull(request.legalAddress()));
        party.setPostalAddress(blankToNull(request.postalAddress()));
        party.setEmail(blankToNull(request.email()));
        party.setPhone(blankToNull(request.phone()));
        party.setUpdatedBy(userId);
    }

    private void ensureEditableParty(ClaimParty party) {
        if (party.getType() == PartyType.EXPEDITOR) {
            throw ClaimException.forbidden(
                "Экспедитор управляется через auth_organizations"
            );
        }
    }

    private void ensureNotManagedExpeditor(PartyType type) {
        if (type == PartyType.EXPEDITOR) {
            throw ClaimException.validation(
                "Экспедитор создаётся только через auth_organizations"
            );
        }
    }

    public PartyResponse toResponse(ClaimParty party) {
        return new PartyResponse(
            party.getId(),
            party.getOrganizationId(),
            party.getType(),
            party.getName(),
            party.getInn(),
            party.getKpp(),
            party.getOgrn(),
            party.getLegalAddress(),
            party.getPostalAddress(),
            party.getEmail(),
            party.getPhone(),
            party.isActive(),
            party.getCreatedAt(),
            party.getUpdatedAt()
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
