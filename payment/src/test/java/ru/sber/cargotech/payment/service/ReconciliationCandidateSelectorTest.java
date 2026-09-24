package ru.sber.cargotech.payment.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.payment.enums.PaymentTargetType;
import ru.sber.cargotech.payment.repository.PaymentTargetCandidate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationCandidateSelectorTest {

    @Test
    void selectsOnlyOpenClaimEvenWhenPaymentExceedsRemainingDebt() {
        PaymentTargetCandidate candidate = candidate("30000.00");

        var selected = ReconciliationCandidateSelector.select(
                List.of(candidate),
                new BigDecimal("30100.00")
        );

        assertEquals(candidate, selected.orElseThrow());
    }

    @Test
    void selectsUniqueExactAmountAmongSeveralClaims() {
        PaymentTargetCandidate exact = candidate("30100.00");

        var selected = ReconciliationCandidateSelector.select(
                List.of(candidate("20000.00"), exact),
                new BigDecimal("30100.00")
        );

        assertEquals(exact, selected.orElseThrow());
    }

    @Test
    void doesNotGuessWhenSeveralClaimsHaveNoExactAmount() {
        var selected = ReconciliationCandidateSelector.select(
                List.of(candidate("20000.00"), candidate("30000.00")),
                new BigDecimal("30100.00")
        );

        assertTrue(selected.isEmpty());
    }

    private PaymentTargetCandidate candidate(String remainingAmount) {
        return new PaymentTargetCandidate(
                PaymentTargetType.CLAIM,
                UUID.randomUUID(),
                "CLM-TEST",
                new BigDecimal("80000.00"),
                new BigDecimal(remainingAmount)
        );
    }
}
