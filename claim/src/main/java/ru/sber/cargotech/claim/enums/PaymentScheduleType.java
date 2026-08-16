package ru.sber.cargotech.claim.enums;

/** Optional contractual rule that shifts a calculated due date to an allowed payment day. */
public enum PaymentScheduleType {
    /** Calculated due date itself may be used when it is an allowed payment day. */
    NEXT_PAYMENT_DAY,

    /** The first allowed payment day strictly after the calculated contractual term expires. */
    NEXT_PAYMENT_DAY_AFTER_TERM
}
