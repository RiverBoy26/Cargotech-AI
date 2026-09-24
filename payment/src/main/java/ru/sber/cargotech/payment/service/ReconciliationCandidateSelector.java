package ru.sber.cargotech.payment.service;

import ru.sber.cargotech.payment.repository.PaymentTargetCandidate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

final class ReconciliationCandidateSelector {

    private ReconciliationCandidateSelector() {
    }

    static Optional<PaymentTargetCandidate> select(
            List<PaymentTargetCandidate> candidates,
            BigDecimal paymentAmount
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        List<PaymentTargetCandidate> exactCandidates = candidates.stream()
                .filter(candidate -> candidate.remainingAmount()
                        .compareTo(paymentAmount.abs()) == 0)
                .toList();

        if (exactCandidates.size() == 1) {
            return Optional.of(exactCandidates.getFirst());
        }

        if (candidates.size() == 1) {
            return Optional.of(candidates.getFirst());
        }

        return Optional.empty();
    }
}
