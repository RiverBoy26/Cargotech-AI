package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ShipmentRequest;
import ru.sber.cargotech.claim.dto.ShipmentResponse;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ContractStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.ShipmentStatus;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimShipmentRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Moscow");
    private static final EnumSet<PaymentStartEvent> REQUIRES_PAYMENT_START_EVENT_DATE = EnumSet.of(
        PaymentStartEvent.REGISTRY_INCLUDED,
        PaymentStartEvent.DOCUMENT_PACKAGE_RECEIVED,
        PaymentStartEvent.LATEST_ACT_OR_DOCUMENT_PACKAGE,
        PaymentStartEvent.ACT_SIGNED_REQUIRES_DOCUMENT_PACKAGE
    );
    private final ClaimShipmentRepository shipmentRepository;
    private final PartyService partyService;
    private final ContractService contractService;
    private final ClaimOutboxWriter outboxWriter;

    @Transactional(readOnly = true)
    public Page<ShipmentResponse> list(CurrentClaimUser user, Pageable pageable) {
        log.debug("Получение рейсов: organizationId={}, page={}, size={}", user.organizationId(), pageable.getPageNumber(), pageable.getPageSize());

        return shipmentRepository
            .findByOrganizationId(user.organizationId(), pageable)
            .map(shipment -> toResponse(user.organizationId(), shipment));
    }

    @Transactional(readOnly = true)
    public ShipmentResponse get(CurrentClaimUser user, UUID id) {
        log.debug("Получение рейса: shipmentId={}, organizationId={}", id, user.organizationId());

        return toResponse(user.organizationId(), getEntity(user.organizationId(), id));
    }

    @Transactional
    public ShipmentResponse create(CurrentClaimUser user, ShipmentRequest request) {
        UUID expeditorId = user.organizationId();
        log.debug("Создание рейса: organizationId={}, userId={}, orderNumber={}, clientId={}, expeditorId={}, contractId={}, serviceAmount={}, status={}", user.organizationId(), user.userId(), request.orderNumber(), request.clientId(), expeditorId, request.contractId(), request.serviceAmount(), request.status());

        validateReferences(user.organizationId(), request, expeditorId);
        ClaimShipment shipment = new ClaimShipment();
        shipment.setOrganizationId(user.organizationId());
        shipment.setCreatedBy(user.userId());
        shipment.setUpdatedBy(user.userId());
        apply(shipment, request, user.userId(), expeditorId, false);
        ClaimShipment saved = shipmentRepository.save(shipment);
        outboxWriter.write("SHIPMENT", saved.getId(), "SHIPMENT_CREATED", user.organizationId(), user.userId(), Map.of("shipmentId", saved.getId()));
        return toResponse(user.organizationId(), saved);
    }

    @Transactional
    public ShipmentResponse update(CurrentClaimUser user, UUID id, ShipmentRequest request) {
        log.debug("Обновление рейса: shipmentId={}, organizationId={}, userId={}, orderNumber={}, serviceAmount={}, status={}", id, user.organizationId(), user.userId(), request.orderNumber(), request.serviceAmount(), request.status());

        UUID expeditorId = user.organizationId();
        validateReferences(user.organizationId(), request, expeditorId);
        ClaimShipment shipment = getEntity(user.organizationId(), id);
        apply(shipment, request, user.userId(), expeditorId, true);
        ClaimShipment saved = shipmentRepository.save(shipment);
        outboxWriter.write("SHIPMENT", saved.getId(), "SHIPMENT_UPDATED", user.organizationId(), user.userId(), Map.of("shipmentId", saved.getId()));
        return toResponse(user.organizationId(), saved);
    }

    @Transactional
    public void delete(CurrentClaimUser user, UUID id) {
        ClaimShipment shipment = getEntity(user.organizationId(), id);
        shipmentRepository.delete(shipment);
        outboxWriter.write(
            "SHIPMENT",
            shipment.getId(),
            "SHIPMENT_DELETED",
            user.organizationId(),
            user.userId(),
            Map.of("shipmentId", shipment.getId())
        );
    }

    public ClaimShipment getEntity(UUID organizationId, UUID id) {
        return shipmentRepository.findByIdAndOrganizationId(id, organizationId)
            .orElseThrow(() -> ClaimException.notFound("Рейс не найден"));
    }

    private void apply(
        ClaimShipment shipment,
        ShipmentRequest request,
        UUID userId,
        UUID expeditorId,
        boolean allowCancellation
    ) {
        boolean cancelled = allowCancellation
            && (shipment.getStatus() == ShipmentStatus.CANCELLED
                || request.status() == ShipmentStatus.CANCELLED);
        shipment.setOrderNumber(request.orderNumber());
        shipment.setClientId(request.clientId());
        shipment.setExpeditorId(expeditorId);
        shipment.setContractId(request.contractId());
        shipment.setRouteFrom(request.routeFrom());
        shipment.setRouteTo(request.routeTo());
        shipment.setLoadingDate(request.loadingDate());
        shipment.setUnloadingDate(request.unloadingDate());
        shipment.setActSignedAt(request.actSignedAt());
        shipment.setTtnSignedAt(request.ttnSignedAt());
        shipment.setInvoiceDate(request.invoiceDate());
        shipment.setPaymentStartEventDate(request.paymentStartEventDate());
        shipment.setServiceAmount(request.serviceAmount());
        shipment.setCurrency(request.currency() == null || request.currency().isBlank() ? "RUB" : request.currency());
        shipment.setStatus(cancelled
            ? ShipmentStatus.CANCELLED
            : ShipmentStatusResolver.resolve(
                request.loadingDate(),
                request.unloadingDate(),
                LocalDate.now(BUSINESS_ZONE)
            ));
        shipment.setExternalId(request.externalId());
        shipment.setUpdatedBy(userId);
    }

    private void validateReferences(
        UUID organizationId,
        ShipmentRequest request,
        UUID expeditorId
    ) {
        if (request.clientId().equals(expeditorId)) {
            throw ClaimException.validation("Клиент и экспедитор в рейсе должны быть разными контрагентами");
        }
        partyService.getEntity(organizationId, request.clientId());
        partyService.getEntity(organizationId, expeditorId);
        ClaimContract contract = contractService.getEntity(organizationId, request.contractId());
        if (contract.getStatus() != ContractStatus.ACTIVE || contract.getNumber() == null) {
            throw ClaimException.validation("Рейс можно привязать только к подтверждённому действующему договору");
        }
        if (REQUIRES_PAYMENT_START_EVENT_DATE.contains(contract.getPaymentStartEvent())
                && request.paymentStartEventDate() == null) {
            throw ClaimException.validation(
                "Заполните обязательное поле «Дата договорного события начала срока оплаты»"
            );
        }
        if (!contract.getClientId().equals(request.clientId()) || !contract.getExpeditorId().equals(expeditorId)) {
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
            shipment.getTtnSignedAt(),
            shipment.getInvoiceDate(),
            shipment.getPaymentStartEventDate(),
            shipment.getServiceAmount(),
            shipment.getCurrency(),
            shipment.getStatus(),
            shipment.getExternalId(),
            shipment.getCreatedAt(),
            shipment.getUpdatedAt()
        );
    }
}
