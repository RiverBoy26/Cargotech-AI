package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.OrganizationProjectionRequest;
import ru.sber.cargotech.claim.dto.PartyResponse;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.enums.PartyType;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimPartyRepository;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationProjectionService {

    private final ClaimPartyRepository partyRepository;
    private final ClaimOutboxWriter outboxWriter;
    private final PartyService partyService;

    @Transactional
    public PartyResponse synchronize(
        OrganizationProjectionRequest request,
        UUID actorUserId
    ) {
        ClaimParty party = partyRepository.findById(request.organizationId())
            .orElseGet(() -> {
                ClaimParty created = new ClaimParty();
                created.setId(request.organizationId());
                created.setOrganizationId(request.organizationId());
                created.setCreatedBy(actorUserId);
                return created;
            });

        if (party.getType() != null && party.getType() != PartyType.EXPEDITOR) {
            throw ClaimException.conflict(
                "UUID организации уже используется другим контрагентом"
            );
        }

        party.setOrganizationId(request.organizationId());
        party.setType(PartyType.EXPEDITOR);
        party.setName(request.name().trim());
        party.setInn(blankToNull(request.inn()));
        party.setKpp(blankToNull(request.kpp()));
        party.setOgrn(blankToNull(request.ogrn()));
        party.setLegalAddress(blankToNull(request.legalAddress()));
        party.setPostalAddress(blankToNull(request.postalAddress()));
        party.setEmail(blankToNull(request.email()));
        party.setPhone(blankToNull(request.phone()));
        party.setActive(request.active());
        party.setDeletedAt(null);
        party.setUpdatedBy(actorUserId);

        ClaimParty saved = partyRepository.save(party);
        outboxWriter.write(
            "PARTY",
            saved.getId(),
            "ORGANIZATION_EXPEDITOR_SYNCHRONIZED",
            saved.getOrganizationId(),
            actorUserId,
            Map.of(
                "partyId",
                saved.getId(),
                "active",
                saved.isActive()
            )
        );
        return partyService.toResponse(saved);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
