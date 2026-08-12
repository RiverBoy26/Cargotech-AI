package ru.sber.cargotech.claim.service;

import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.TermDayType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Centralized payment-deadline and overdue-day rules for every claim view. */
public final class OverdueDateCalculator {
    private OverdueDateCalculator() {
    }

    public static LocalDate overdueStartDate(ClaimShipment shipment, ClaimContract contract) {
        PaymentStartEvent configuredEvent = contract.getPaymentStartEvent();
        LocalDate baseDate;

        if (configuredEvent == null) {
            // Product fallback is used only when the contract does not define an anchor.
            baseDate = shipment.getActSignedAt() != null
                ? shipment.getActSignedAt()
                : shipment.getUnloadingDate();
        } else {
            baseDate = switch (configuredEvent) {
                case ACT_SIGNED -> shipment.getActSignedAt();
                case UNLOADING_DATE -> shipment.getUnloadingDate();
                case TTN_SIGNED -> shipment.getTtnSignedAt();
                case INVOICE_DATE -> shipment.getInvoiceDate();
                case REGISTRY_INCLUDED, DOCUMENT_PACKAGE_RECEIVED -> shipment.getPaymentStartEventDate();
            };
        }

        // Never silently replace an explicit contractual anchor with another shipment date.
        if (baseDate == null) {
            return null;
        }

        int paymentDays = contract.getPaymentDays() == null ? 0 : contract.getPaymentDays();
        TermDayType dayType = contract.getPaymentDayType() == null
            ? TermDayType.CALENDAR_DAYS
            : contract.getPaymentDayType();
        LocalDate dueDate = addTermDays(baseDate, paymentDays, dayType);
        return dueDate.plusDays(1);
    }

    static LocalDate addTermDays(LocalDate baseDate, int days, TermDayType dayType) {
        if (days <= 0) return baseDate;
        if (dayType == TermDayType.CALENDAR_DAYS) {
            return baseDate.plusDays(days);
        }

        // MVP calendar: working/banking days exclude weekends. Public-holiday overrides
        // should be supplied by a production calendar service before industrial use.
        LocalDate cursor = baseDate;
        int remaining = days;
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            DayOfWeek day = cursor.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return cursor;
    }

    public static int overdueDays(LocalDate overdueStartDate, LocalDate calculationDate) {
        if (overdueStartDate == null || !calculationDate.isAfter(overdueStartDate)) {
            return 0;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(overdueStartDate, calculationDate));
    }
}
