package ru.sber.cargotech.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.payment.entity.PaymentReconciliationRun;

import java.util.Optional;
import java.util.UUID;

public interface PaymentReconciliationRunRepository
    extends JpaRepository<PaymentReconciliationRun, UUID> {

    Optional<PaymentReconciliationRun> findByIdAndOrganizationId(
        UUID id,
        UUID organizationId
    );
}
