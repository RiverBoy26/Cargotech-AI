package ru.sber.cargotech.claim.service;

import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.ContractExtractionStatus;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PaymentScheduleType;
import ru.sber.cargotech.claim.enums.TermDayType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

/** Centralized payment-deadline and overdue-day rules for every claim view. */
public final class OverdueDateCalculator {
    private OverdueDateCalculator() {
    }

    public static LocalDate overdueStartDate(ClaimShipment shipment, ClaimContract contract) {
        LocalDate dueDate = paymentDueDate(shipment, contract);
        return dueDate == null ? null : dueDate.plusDays(1);
    }

    static LocalDate paymentDueDate(ClaimShipment shipment, ClaimContract contract) {
        PaymentStartEvent configuredEvent = contract.getPaymentStartEvent();
        LocalDate effectiveDate = contract.getValidFrom() != null
            ? contract.getValidFrom()
            : contract.getSignedAt();

        LocalDate baseDate;
        if (configuredEvent == null) {
            /*
             * Backward compatibility for contracts that were created manually before the
             * parser existed. A confirmed parsed contract is different: null there means
             * "the parser/reviewer could not safely represent the contractual anchor".
             * That state must never silently become ACT_SIGNED.
             */
            boolean confirmedParsedContract = contract.getDocumentId() != null
                && contract.getExtractionStatus() == ContractExtractionStatus.CONFIRMED
                && contract.getPaymentDays() != null;
            if (confirmedParsedContract) {
                return null;
            }
            baseDate = shipment.getActSignedAt() != null
                ? shipment.getActSignedAt()
                : shipment.getUnloadingDate();
            if (!notBeforeEffective(baseDate, effectiveDate)) return null;
        } else {
            switch (configuredEvent) {
                case LATEST_ACT_OR_DOCUMENT_PACKAGE -> {
                    LocalDate actDate = shipment.getActSignedAt();
                    LocalDate documentsDate = shipment.getPaymentStartEventDate();
                    if (!notBeforeEffective(actDate, effectiveDate)
                            || !notBeforeEffective(documentsDate, effectiveDate)) {
                        return null;
                    }
                    baseDate = actDate.isAfter(documentsDate) ? actDate : documentsDate;
                }
                case ACT_SIGNED_REQUIRES_DOCUMENT_PACKAGE -> {
                    LocalDate actDate = shipment.getActSignedAt();
                    LocalDate documentsDate = shipment.getPaymentStartEventDate();
                    if (!notBeforeEffective(actDate, effectiveDate)
                            || !notBeforeEffective(documentsDate, effectiveDate)) {
                        return null;
                    }

                    int paymentDays = paymentDays(contract);
                    TermDayType dayType = dayType(contract);
                    LocalDate nominalDueDate = addTermDays(actDate, paymentDays, dayType);

                    /*
                     * "N days from the act, provided the documents were received" does not
                     * define how a late document package shifts an already expired term.
                     * Do not invent a shift. Such a case requires a lawyer.
                     */
                    if (documentsDate.isAfter(nominalDueDate)) {
                        return null;
                    }
                    return applyPaymentSchedule(nominalDueDate, contract);
                }
                case ACT_SIGNED -> baseDate = shipment.getActSignedAt();
                case UNLOADING_DATE -> baseDate = shipment.getUnloadingDate();
                case TTN_SIGNED -> baseDate = shipment.getTtnSignedAt();
                case INVOICE_DATE -> baseDate = shipment.getInvoiceDate();
                case REGISTRY_INCLUDED, DOCUMENT_PACKAGE_RECEIVED -> baseDate = shipment.getPaymentStartEventDate();
                default -> {
                    return null;
                }
            }
            if (!notBeforeEffective(baseDate, effectiveDate)) return null;
        }

        LocalDate dueDate = addTermDays(baseDate, paymentDays(contract), dayType(contract));
        return applyPaymentSchedule(dueDate, contract);
    }

    static LocalDate applyPaymentSchedule(LocalDate dueDate, ClaimContract contract) {
        if (dueDate == null || contract.getPaymentScheduleType() == null) {
            return dueDate;
        }

        Set<DayOfWeek> allowedDays = parsePaymentWeekDays(contract.getPaymentWeekDays());
        if (allowedDays.isEmpty()) {
            // A partially configured schedule is not usable for a deterministic deadline.
            return null;
        }

        LocalDate cursor = switch (contract.getPaymentScheduleType()) {
            case NEXT_PAYMENT_DAY -> dueDate;
            case NEXT_PAYMENT_DAY_AFTER_TERM -> dueDate.plusDays(1);
        };
        for (int i = 0; i < 7; i++) {
            if (allowedDays.contains(cursor.getDayOfWeek())) {
                return cursor;
            }
            cursor = cursor.plusDays(1);
        }
        return null;
    }

    static Set<DayOfWeek> parsePaymentWeekDays(String value) {
        if (value == null || value.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        Set<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        for (String token : value.split(",")) {
            String item = token.trim();
            if (item.isEmpty()) continue;
            try {
                result.add(DayOfWeek.valueOf(item));
            } catch (IllegalArgumentException ignored) {
                // Invalid persisted values make the schedule unusable rather than shifting to a wrong date.
                return EnumSet.noneOf(DayOfWeek.class);
            }
        }
        return result;
    }

    static LocalDate addTermDays(LocalDate baseDate, int days, TermDayType dayType) {
        if (baseDate == null) return null;
        if (days <= 0) return baseDate;
        if (dayType == TermDayType.CALENDAR_DAYS) {
            return baseDate.plusDays(days);
        }

        /*
         * MVP business calendar: working/banking days exclude weekends only.
         * Public-holiday overrides require a production RF calendar service.
         * Until then this limitation must be covered by acceptance/manual review
         * for periods crossing official holidays.
         */
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

    private static int paymentDays(ClaimContract contract) {
        return contract.getPaymentDays() == null ? 0 : contract.getPaymentDays();
    }

    private static TermDayType dayType(ClaimContract contract) {
        return contract.getPaymentDayType() == null
            ? TermDayType.CALENDAR_DAYS
            : contract.getPaymentDayType();
    }

    private static boolean notBeforeEffective(LocalDate date, LocalDate effectiveDate) {
        return date != null && (effectiveDate == null || !date.isBefore(effectiveDate));
    }
}
