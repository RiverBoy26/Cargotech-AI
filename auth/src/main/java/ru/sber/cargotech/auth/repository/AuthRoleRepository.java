package ru.sber.cargotech.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sber.cargotech.auth.entity.AuthRole;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthRoleRepository extends JpaRepository<AuthRole, UUID> {

    Optional<AuthRole> findByCode(String code);

    List<AuthRole> findAllByCodeIn(Collection<String> codes);

    List<AuthRole> findAllByOrderByCodeAsc();
}
