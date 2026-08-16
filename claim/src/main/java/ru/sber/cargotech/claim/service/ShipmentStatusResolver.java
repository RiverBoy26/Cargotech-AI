package ru.sber.cargotech.claim.service;

import ru.sber.cargotech.claim.enums.ShipmentStatus;

import java.time.LocalDate;

/** Determines the operational shipment status from its loading and unloading dates. */
public final class ShipmentStatusResolver {
    private ShipmentStatusResolver() {
    }

    public static ShipmentStatus resolve(
        LocalDate loadingDate,
        LocalDate unloadingDate,
        LocalDate today
    ) {
        if (unloadingDate != null && !unloadingDate.isAfter(today)) {
            return ShipmentStatus.COMPLETED;
        }
        if (loadingDate != null && !loadingDate.isAfter(today)) {
            return ShipmentStatus.IN_PROGRESS;
        }
        return ShipmentStatus.CREATED;
    }
}
