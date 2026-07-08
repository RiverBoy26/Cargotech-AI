package ru.sber.cargotech.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.payment.entity.PaymentImport;

import java.util.UUID;

public interface PaymentImportRepository
    extends JpaRepository<PaymentImport, UUID> {
}
