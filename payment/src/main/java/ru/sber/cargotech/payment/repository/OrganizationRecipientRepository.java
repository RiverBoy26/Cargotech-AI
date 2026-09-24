package ru.sber.cargotech.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.payment.entity.OrganizationRecipient;

import java.util.UUID;

public interface OrganizationRecipientRepository
    extends JpaRepository<OrganizationRecipient, UUID> {
}
