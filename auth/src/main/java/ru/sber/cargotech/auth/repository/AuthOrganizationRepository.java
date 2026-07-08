package ru.sber.cargotech.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.auth.entity.AuthOrganization;

import java.util.Optional;
import java.util.UUID;

public interface AuthOrganizationRepository
    extends JpaRepository<AuthOrganization, UUID> {

    Optional<AuthOrganization> findByInn(String inn);

    Optional<AuthOrganization> findFirstByNameIgnoreCase(String name);
}
