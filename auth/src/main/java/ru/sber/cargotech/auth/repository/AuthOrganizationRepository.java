package ru.sber.cargotech.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.sber.cargotech.auth.entity.AuthOrganization;

import java.util.Optional;
import java.util.UUID;

public interface AuthOrganizationRepository
    extends JpaRepository<AuthOrganization, UUID>,
    JpaSpecificationExecutor<AuthOrganization> {

    Optional<AuthOrganization> findByInn(String inn);

    Optional<AuthOrganization> findFirstByNameIgnoreCase(String name);

    boolean existsByInnAndIdNot(String inn, UUID id);
}
