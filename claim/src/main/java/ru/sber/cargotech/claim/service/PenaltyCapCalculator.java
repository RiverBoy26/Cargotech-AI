package ru.sber.cargotech.claim.service;

import ru.sber.cargotech.claim.enums.PenaltyCapBase;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class PenaltyCapCalculator {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private PenaltyCapCalculator() {
    }

    static BigDecimal apply(
        BigDecimal accruedPenalty,
        BigDecimal capPercent,
        PenaltyCapBase capBase,
        BigDecimal principalDebt,
        BigDecimal paidAmount
    ) {
        BigDecimal penalty = money(accruedPenalty);
        if (penalty.signum() <= 0 || capPercent == null || capBase == null || capPercent.signum() < 0) {
            return penalty;
        }

        BigDecimal reference = switch (capBase) {
            case OUTSTANDING_DEBT -> money(principalDebt)
                .subtract(money(paidAmount))
                .max(BigDecimal.ZERO);
            case PRINCIPAL_DEBT, SHIPMENT_COST, INVOICE_AMOUNT -> money(principalDebt);
        };
        BigDecimal maximum = reference
            .multiply(capPercent)
            .divide(ONE_HUNDRED, 12, RoundingMode.HALF_UP);
        return money(penalty.min(maximum));
    }

    static String describe(BigDecimal capPercent, PenaltyCapBase capBase) {
        if (capPercent == null || capBase == null) return "";
        return "; максимум " + capPercent.stripTrailingZeros().toPlainString() + "% от " + switch (capBase) {
            case PRINCIPAL_DEBT -> "основного долга";
            case OUTSTANDING_DEBT -> "непогашенной задолженности";
            case SHIPMENT_COST -> "стоимости соответствующей перевозки";
            case INVOICE_AMOUNT -> "суммы соответствующего счёта";
        };
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
