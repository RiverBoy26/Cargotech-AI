package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PaymentScheduleType;
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

    @Test
    void nextPaymentDayScheduleShiftsDueDateToAllowedWeekday() {
        ClaimContract contract = new ClaimContract();
        contract.setPaymentStartEvent(PaymentStartEvent.DOCUMENT_PACKAGE_RECEIVED);
        contract.setPaymentDays(45);
        contract.setPaymentDayType(TermDayType.CALENDAR_DAYS);
        contract.setPaymentScheduleType(PaymentScheduleType.NEXT_PAYMENT_DAY);
        contract.setPaymentWeekDays("TUESDAY,THURSDAY");

        ClaimShipment shipment = new ClaimShipment();
        shipment.setPaymentStartEventDate(LocalDate.of(2026, 6, 1));

        // 01.06 + 45 calendar days = Thursday 16.07.2026, already an allowed payment day.
        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract))
            .isEqualTo(LocalDate.of(2026, 7, 17));

        shipment.setPaymentStartEventDate(LocalDate.of(2026, 6, 2));
        // 02.06 + 45 = Friday 17.07; next allowed payment day is Tuesday 21.07.
        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract))
            .isEqualTo(LocalDate.of(2026, 7, 22));
    }

    @Test
    void incompletePaymentScheduleDoesNotInventDeadline() {
        ClaimContract contract = new ClaimContract();
        contract.setPaymentStartEvent(PaymentStartEvent.ACT_SIGNED);
        contract.setPaymentDays(5);
        contract.setPaymentDayType(TermDayType.CALENDAR_DAYS);
        contract.setPaymentScheduleType(PaymentScheduleType.NEXT_PAYMENT_DAY);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setActSignedAt(LocalDate.of(2026, 8, 1));

        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract)).isNull();
    }

}
