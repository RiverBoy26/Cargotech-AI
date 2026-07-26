package ru.sber.cargotech.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.sber.cargotech.auth.entity.AuthUser;

import java.util.Optional;
import java.util.UUID;

public interface AuthUserRepository
    extends JpaRepository<AuthUser, UUID>, JpaSpecificationExecutor<AuthUser> {

    Optional<AuthUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, UUID id);
}
