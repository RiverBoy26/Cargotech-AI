package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ShipmentStatus;
import ru.sber.cargotech.claim.repository.ClaimOutboxWriter;
import ru.sber.cargotech.claim.repository.ClaimShipmentRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShipmentStatusScheduler {
    private static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Moscow");

    private final ClaimShipmentRepository shipmentRepository;
    private final ClaimOutboxWriter outboxWriter;

    /** Updates statuses after the business date changes. Cancelled shipments remain cancelled. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${shipment.status.daily-cron:0 5 1 * * *}", zone = "${claim.calculation.zone:Europe/Moscow}")
    @Transactional
    public void refreshStatuses() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        for (ClaimShipment shipment : shipmentRepository.findAllByStatusNot(ShipmentStatus.CANCELLED)) {
            ShipmentStatus previousStatus = shipment.getStatus();
            ShipmentStatus newStatus = ShipmentStatusResolver.resolve(
                shipment.getLoadingDate(),
                shipment.getUnloadingDate(),
                today
            );
            if (newStatus == previousStatus) continue;

            shipment.setStatus(newStatus);
            shipment.setUpdatedAt(OffsetDateTime.now(BUSINESS_ZONE));
            shipment.setUpdatedBy(SYSTEM_USER_ID);
            shipmentRepository.save(shipment);
            outboxWriter.write(
                "SHIPMENT",
                shipment.getId(),
                "SHIPMENT_STATUS_CHANGED",
                shipment.getOrganizationId(),
                SYSTEM_USER_ID,
                Map.of("shipmentId", shipment.getId(), "previousStatus", previousStatus, "newStatus", newStatus)
            );
        }
    }
}
