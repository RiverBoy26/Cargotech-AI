package ru.sber.cargotech.claim.service;

import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Centralized payment-deadline and overdue-day rules for every claim view. */
public final class OverdueDateCalculator {
    private OverdueDateCalculator() {
    }

    public static LocalDate overdueStartDate(ClaimShipment shipment, ClaimContract contract) {
        LocalDate baseDate = switch (contract.getPaymentStartEvent() == null
            ? PaymentStartEvent.UNLOADING_DATE
            : contract.getPaymentStartEvent()) {
            case ACT_SIGNED -> shipment.getActSignedAt();
            case UNLOADING_DATE -> shipment.getUnloadingDate();
            case TTN_SIGNED -> shipment.getTtnSignedAt();
            case INVOICE_DATE -> shipment.getInvoiceDate();
        };
        if (baseDate == null) {
            baseDate = shipment.getActSignedAt() != null
                ? shipment.getActSignedAt()
                : shipment.getUnloadingDate();
        }
        if (baseDate == null) {
            return null;
        }
        int paymentDays = contract.getPaymentDays() == null ? 0 : contract.getPaymentDays();
        return baseDate.plusDays(paymentDays + 1L);
    }

    public static int overdueDays(LocalDate overdueStartDate, LocalDate calculationDate) {
        if (overdueStartDate == null || !calculationDate.isAfter(overdueStartDate)) {
            return 0;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(overdueStartDate, calculationDate));
    }
}
