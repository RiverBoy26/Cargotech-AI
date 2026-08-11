package ru.sber.cargotech.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.payment.entity.PaymentCheck;

import java.util.UUID;

public interface PaymentCheckRepository
    extends JpaRepository<PaymentCheck, UUID> {
}
