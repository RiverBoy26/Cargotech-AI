package ru.sber.cargotech.claim.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ShipmentRequest;
import ru.sber.cargotech.claim.dto.ShipmentResponse;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ShipmentStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimShipmentRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.util.Map;
import java.util.UUID;

@Service
public class ShipmentService {
    private final ClaimShipmentRepository shipmentRepository;
    private final PartyService partyService;
    private final ContractService contractService;
    private final ClaimOutboxWriter outboxWriter;

    public ShipmentService(
        ClaimShipmentRepository shipmentRepository,
        PartyService partyService,
        ContractService contractService,
        ClaimOutboxWriter outboxWriter
    ) {
        this.shipmentRepository = shipmentRepository;
        this.partyService = partyService;
        this.contractService = contractService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public Page<ShipmentResponse> list(CurrentClaimUser user, Pageable pageable) {
        return shipmentRepository
            .findByOrganizationId(user.organizationId(), pageable)
            .map(shipment -> toResponse(user.organizationId(), shipment));
    }

    @Transactional(readOnly = true)
    public ShipmentResponse get(CurrentClaimUser user, UUID id) {
        return toResponse(user.organizationId(), getEntity(user.organizationId(), id));
    }

    @Transactional
    public ShipmentResponse create(CurrentClaimUser user, ShipmentRequest request) {
        validateReferences(user.organizationId(), request);
        ClaimShipment shipment = new ClaimShipment();
        shipment.setOrganizationId(user.organizationId());
        shipment.setCreatedBy(user.userId());
        shipment.setUpdatedBy(user.userId());
        apply(shipment, request, user.userId());
        ClaimShipment saved = shipmentRepository.save(shipment);
        outboxWriter.write("SHIPMENT", saved.getId(), "SHIPMENT_CREATED", user.organizationId(), user.userId(), Map.of("shipmentId", saved.getId()));
        return toResponse(user.organizationId(), saved);
    }

    @Transactional
    public ShipmentResponse update(CurrentClaimUser user, UUID id, ShipmentRequest request) {
        validateReferences(user.organizationId(), request);
        ClaimShipment shipment = getEntity(user.organizationId(), id);
        apply(shipment, request, user.userId());
        ClaimShipment saved = shipmentRepository.save(shipment);
        outboxWriter.write("SHIPMENT", saved.getId(), "SHIPMENT_UPDATED", user.organizationId(), user.userId(), Map.of("shipmentId", saved.getId()));
        return toResponse(user.organizationId(), saved);
    }

    public ClaimShipment getEntity(UUID organizationId, UUID id) {
        return shipmentRepository.findByIdAndOrganizationId(id, organizationId)
            .orElseThrow(() -> ClaimException.notFound("Рейс не найден"));
    }

    private void apply(ClaimShipment shipment, ShipmentRequest request, UUID userId) {
        shipment.setOrderNumber(request.orderNumber());
        shipment.setClientId(request.clientId());
        shipment.setExpeditorId(request.expeditorId());
        shipment.setContractId(request.contractId());
        shipment.setRouteFrom(request.routeFrom());
        shipment.setRouteTo(request.routeTo());
        shipment.setLoadingDate(request.loadingDate());
        shipment.setUnloadingDate(request.unloadingDate());
        shipment.setActSignedAt(request.actSignedAt());
        shipment.setServiceAmount(request.serviceAmount());
        shipment.setCurrency(request.currency() == null || request.currency().isBlank() ? "RUB" : request.currency());
        shipment.setStatus(request.status() == null ? ShipmentStatus.CREATED : request.status());
        shipment.setExternalId(request.externalId());
        shipment.setUpdatedBy(userId);
    }

    private void validateReferences(UUID organizationId, ShipmentRequest request) {
        if (request.clientId().equals(request.expeditorId())) {
            throw ClaimException.validation("Клиент и экспедитор в рейсе должны быть разными контрагентами");
        }
        partyService.getEntity(organizationId, request.clientId());
        partyService.getEntity(organizationId, request.expeditorId());
        ClaimContract contract = contractService.getEntity(organizationId, request.contractId());
        if (!contract.getClientId().equals(request.clientId()) || !contract.getExpeditorId().equals(request.expeditorId())) {
            throw ClaimException.validation("Клиент и экспедитор рейса должны совпадать с договором");
        }
    }

    public ShipmentResponse toResponse(UUID organizationId, ClaimShipment shipment) {
        ClaimParty client = partyService.getEntity(organizationId, shipment.getClientId());
        ClaimParty expeditor = partyService.getEntity(organizationId, shipment.getExpeditorId());
        ClaimContract contract = contractService.getEntity(organizationId, shipment.getContractId());
        return new ShipmentResponse(
            shipment.getId(),
            shipment.getOrganizationId(),
            shipment.getOrderNumber(),
            shipment.getClientId(),
            client.getName(),
            shipment.getExpeditorId(),
            expeditor.getName(),
            shipment.getContractId(),
            contract.getNumber(),
            shipment.getRouteFrom(),
            shipment.getRouteTo(),
            shipment.getLoadingDate(),
            shipment.getUnloadingDate(),
            shipment.getActSignedAt(),
            shipment.getServiceAmount(),
            shipment.getCurrency(),
            shipment.getStatus(),
            shipment.getExternalId(),
            shipment.getCreatedAt(),
            shipment.getUpdatedAt()
        );
    }
}
