package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PaymentScheduleType;
import ru.sber.cargotech.claim.enums.TermDayType;

import java.time.LocalDate;
import java.util.UUID;

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


    @Test
    void laterOfActAndDocumentsRequiresBothDatesAndUsesLaterOne() {
        ClaimContract contract = new ClaimContract();
        contract.setSignedAt(LocalDate.of(2026, 7, 1));
        contract.setPaymentStartEvent(PaymentStartEvent.LATEST_ACT_OR_DOCUMENT_PACKAGE);
        contract.setPaymentDays(30);
        contract.setPaymentDayType(TermDayType.CALENDAR_DAYS);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setActSignedAt(LocalDate.of(2026, 7, 10));

        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract)).isNull();

        shipment.setPaymentStartEventDate(LocalDate.of(2026, 7, 15));
        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract))
            .isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void actWithDocumentsPrerequisiteBlocksAmbiguousLateDocuments() {
        ClaimContract contract = new ClaimContract();
        contract.setSignedAt(LocalDate.of(2026, 6, 1));
        contract.setPaymentStartEvent(PaymentStartEvent.ACT_SIGNED_REQUIRES_DOCUMENT_PACKAGE);
        contract.setPaymentDays(10);
        contract.setPaymentDayType(TermDayType.CALENDAR_DAYS);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setActSignedAt(LocalDate.of(2026, 6, 5));
        shipment.setPaymentStartEventDate(LocalDate.of(2026, 6, 10));
        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract))
            .isEqualTo(LocalDate.of(2026, 6, 16));

        shipment.setPaymentStartEventDate(LocalDate.of(2026, 6, 20));
        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract)).isNull();
    }

    @Test
    void confirmedParsedContractWithUnknownAnchorDoesNotFallBackToAct() {
        ClaimContract contract = new ClaimContract();
        contract.setDocumentId(UUID.randomUUID());
        contract.setExtractionStatus(ContractExtractionStatus.CONFIRMED);
        contract.setPaymentDays(30);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setActSignedAt(LocalDate.of(2026, 7, 10));

        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract)).isNull();
    }

    @Test
    void contractualAnchorBeforeContractEffectiveDateIsRejected() {
        ClaimContract contract = new ClaimContract();
        contract.setSignedAt(LocalDate.of(2026, 7, 8));
        contract.setPaymentStartEvent(PaymentStartEvent.ACT_SIGNED);
        contract.setPaymentDays(30);

        ClaimShipment shipment = new ClaimShipment();
        shipment.setActSignedAt(LocalDate.of(2026, 6, 16));

        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract)).isNull();
    }

    @Test
    void strictPaymentDayScheduleStartsAfterTermEvenWhenDueDateIsAllowedDay() {
        ClaimContract contract = new ClaimContract();
        contract.setPaymentStartEvent(PaymentStartEvent.DOCUMENT_PACKAGE_RECEIVED);
        contract.setPaymentDays(4);
        contract.setPaymentDayType(TermDayType.CALENDAR_DAYS);
        contract.setPaymentScheduleType(PaymentScheduleType.NEXT_PAYMENT_DAY_AFTER_TERM);
        contract.setPaymentWeekDays("FRIDAY");

        ClaimShipment shipment = new ClaimShipment();
        shipment.setPaymentStartEventDate(LocalDate.of(2026, 8, 3)); // Monday; +4 = Friday.

        // Strict rule says first Friday AFTER the four-day term: 14.08, overdue starts 15.08.
        assertThat(OverdueDateCalculator.overdueStartDate(shipment, contract))
            .isEqualTo(LocalDate.of(2026, 8, 15));
    }


}
