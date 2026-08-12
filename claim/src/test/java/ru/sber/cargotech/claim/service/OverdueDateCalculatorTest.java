package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.TermDayType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OverdueDateCalculatorTest {

    @Test
    void calendarDaysKeepCalendarSemantics() {
        assertThat(OverdueDateCalculator.addTermDays(
            LocalDate.of(2026, 8, 3), 5, TermDayType.CALENDAR_DAYS
        )).isEqualTo(LocalDate.of(2026, 8, 8));
    }

    @Test
    void bankingDaysSkipWeekendInMvpCalendar() {
        assertThat(OverdueDateCalculator.addTermDays(
            LocalDate.of(2026, 8, 7), 5, TermDayType.BANKING_DAYS
        )).isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    void registryAnchorUsesExplicitShipmentEventDate() {
        ClaimContract contract = new ClaimContract();
        contract.setPaymentStartEvent(PaymentStartEvent.REGISTRY_INCLUDED);
        contract.setPaymentDays(5);
        contract.setPaymentDayType(TermDayType.BANKING_DAYS);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setUnloadingDate(LocalDate.of(2026, 8, 1));
        shipment.setActSignedAt(LocalDate.of(2026, 8, 2));
        shipment.setPaymentStartEventDate(LocalDate.of(2026, 8, 7));

        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract))
            .isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void explicitMissingAnchorDoesNotFallBackToAnotherShipmentDate() {
        ClaimContract contract = new ClaimContract();
        contract.setPaymentStartEvent(PaymentStartEvent.REGISTRY_INCLUDED);
        contract.setPaymentDays(5);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setActSignedAt(LocalDate.of(2026, 8, 2));
        shipment.setUnloadingDate(LocalDate.of(2026, 8, 1));

        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract)).isNull();
    }
}
