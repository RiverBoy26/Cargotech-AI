package ru.sber.cargotech.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.auth.dto.RoleResponse;
import ru.sber.cargotech.auth.dto.UserAccess;
import ru.sber.cargotech.auth.entity.AuthOrganization;
import ru.sber.cargotech.auth.entity.AuthRole;
import ru.sber.cargotech.auth.entity.AuthUser;
import ru.sber.cargotech.auth.enums.OrganizationStatus;
import ru.sber.cargotech.auth.exception.AuthException;
import ru.sber.cargotech.auth.repository.AuthOrganizationRepository;
import ru.sber.cargotech.auth.repository.AuthRoleRepository;
import ru.sber.cargotech.auth.repository.UserAccessRepository;
import ru.sber.cargotech.auth.security.CurrentUser;


import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccessService {

    private final AuthOrganizationRepository organizationRepository;
    private final AuthRoleRepository roleRepository;
    private final UserAccessRepository accessRepository;

    @Transactional(readOnly = true)
    public UserAccess load(AuthUser user, UUID organizationContext) {
        log.debug("Загрузка прав пользователя: userId={}, userOrganizationId={}, organizationContext={}", user.getId(), user.getOrganizationId(), organizationContext);

        return new UserAccess(
            user.getId(),
            organizationContext,
            user.getFirstName(),
            user.getLastName(),
            user.getMiddleName(),
            user.getEmail(),
            accessRepository.findRoleCodes(user.getId()),
            accessRepository.findPermissionCodes(user.getId())
        );
    }

    @Transactional(readOnly = true)
    public Set<String> roleCodes(UUID userId) {
        log.debug("Загрузка ролей пользователя: userId={}", userId);

        return accessRepository.findRoleCodes(userId);
    }

    @Transactional(readOnly = true)
    public List<AuthRole> requireRoles(Set<String> roleCodes) {
        log.debug("Проверка существования ролей: requestedRoles={}", roleCodes);

        Set<String> normalized = normalizeRoles(roleCodes);
        List<AuthRole> roles = roleRepository.findAllByCodeIn(normalized);
        Set<String> found = roles.stream()
            .map(AuthRole::getCode)
            .collect(Collectors.toSet());

        Set<String> missing = new TreeSet<>(normalized);
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            throw AuthException.validation(
                "Неизвестные роли: " + String.join(", ", missing)
            );
        }
        return roles;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> assignableRoles(CurrentUser actor) {
        log.debug("Определение назначаемых ролей: actorUserId={}, actorRoles={}", actor.userId(), actor.roles());

        Set<String> allowed = actor.hasRole("SUPER_ADMIN")
            ? Set.of("ACCOUNTANT", "LAWYER", "EXPEDITOR_ADMIN", "SUPER_ADMIN")
            : Set.of("ACCOUNTANT", "LAWYER");

        return roleRepository.findAllByOrderByCodeAsc().stream()
            .filter(role -> allowed.contains(role.getCode()))
            .map(role -> new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription()
            ))
            .toList();
    }

    public AuthOrganization requireActiveOrganization(UUID organizationId) {
        log.debug("Проверка активной организации: organizationId={}", organizationId);

        AuthOrganization organization = organizationRepository
            .findById(organizationId)
            .orElseThrow(() -> AuthException.notFound(
                "Организация %s не найдена".formatted(organizationId)
            ));

        if (organization.getStatus() != OrganizationStatus.ACTIVE) {
            throw AuthException.forbidden("Организация неактивна");
        }
        return organization;
    }

    public Set<String> normalizeRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw AuthException.validation(
                "Пользователю необходимо назначить хотя бы одну роль"
            );
        }
        return roles.stream()
            .map(String::trim)
            .map(value -> value.toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
