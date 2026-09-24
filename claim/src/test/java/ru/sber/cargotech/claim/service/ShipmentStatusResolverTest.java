package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.enums.ShipmentStatus;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentStatusResolverTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Test
    void createdBeforeLoadingDate() {
        assertThat(ShipmentStatusResolver.resolve(TODAY.plusDays(1), null, TODAY))
            .isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void inProgressFromLoadingDate() {
        assertThat(ShipmentStatusResolver.resolve(TODAY, TODAY.plusDays(1), TODAY))
            .isEqualTo(ShipmentStatus.IN_PROGRESS);
    }

    @Test
    void completedFromUnloadingDate() {
        assertThat(ShipmentStatusResolver.resolve(TODAY.minusDays(1), TODAY, TODAY))
            .isEqualTo(ShipmentStatus.COMPLETED);
    }
}
