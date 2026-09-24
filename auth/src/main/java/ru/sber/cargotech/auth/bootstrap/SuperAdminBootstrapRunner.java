package ru.sber.cargotech.auth.bootstrap;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.auth.config.AuthProperties;
import ru.sber.cargotech.auth.entity.AuthOrganization;
import ru.sber.cargotech.auth.entity.AuthRole;
import ru.sber.cargotech.auth.entity.AuthUser;
import ru.sber.cargotech.auth.enums.OrganizationStatus;
import ru.sber.cargotech.auth.exception.AuthException;
import ru.sber.cargotech.auth.repository.AuthOrganizationRepository;
import ru.sber.cargotech.auth.repository.AuthRoleRepository;
import ru.sber.cargotech.auth.repository.AuthUserRepository;
import ru.sber.cargotech.auth.repository.UserAccessRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SuperAdminBootstrapRunner implements ApplicationRunner {

    private final AuthProperties properties;
    private final AuthOrganizationRepository organizationRepository;
    private final AuthUserRepository userRepository;
    private final AuthRoleRepository roleRepository;
    private final UserAccessRepository accessRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AuthProperties.Bootstrap bootstrap = properties.bootstrap();

        if (!bootstrap.enabled()) {
            return;
        }

        validate(bootstrap);

        AuthOrganization organization = findOrCreateOrganization(bootstrap);
        organization.setStatus(OrganizationStatus.ACTIVE);
        AuthOrganization persistedOrganization =
                organizationRepository.saveAndFlush(organization);

        AuthUser user = userRepository
                .findByEmailIgnoreCase(normalizeEmail(bootstrap.email()))
                .orElseGet(() -> createUser(
                    bootstrap,
                    persistedOrganization
                ));

        synchronizeUser(user, bootstrap, persistedOrganization);
        user = userRepository.saveAndFlush(user);

        AuthRole superAdmin = roleRepository.findByCode("SUPER_ADMIN")
                .orElseThrow(() -> AuthException.validation(
                        "Роль SUPER_ADMIN отсутствует в БД"
                ));

        Set<String> currentRoles = accessRepository.findRoleCodes(user.getId());

        if (!currentRoles.contains("SUPER_ADMIN")) {
            List<UUID> roleIds = roleRepository
                    .findAllByCodeIn(currentRoles)
                    .stream()
                    .map(AuthRole::getId)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            roleIds.add(superAdmin.getId());

            accessRepository.replaceRoles(
                    user.getId(),
                    roleIds,
                    null
            );
        }
    }

    private AuthOrganization findOrCreateOrganization(
            AuthProperties.Bootstrap bootstrap
    ) {
        Optional<AuthOrganization> existing =
                bootstrap.organizationInn() != null
                        && !bootstrap.organizationInn().isBlank()
                        ? organizationRepository.findByInn(bootstrap.organizationInn())
                        : organizationRepository.findFirstByNameIgnoreCase(
                        bootstrap.organizationName()
                );

        return existing.orElseGet(() -> {
            AuthOrganization organization = new AuthOrganization();
            organization.setName(bootstrap.organizationName().trim());
            organization.setInn(blankToNull(bootstrap.organizationInn()));
            organization.setStatus(OrganizationStatus.ACTIVE);

            return organization;
        });
    }

    private AuthUser createUser(
            AuthProperties.Bootstrap bootstrap,
            AuthOrganization organization
    ) {
        AuthUser user = new AuthUser();
        user.setOrganizationId(organization.getId());
        user.setFirstName(bootstrap.firstName().trim());
        user.setLastName(bootstrap.lastName().trim());
        user.setMiddleName(blankToNull(bootstrap.middleName()));
        user.setEmail(normalizeEmail(bootstrap.email()));
        user.setPasswordHash(passwordEncoder.encode(bootstrap.password()));
        user.setActive(true);

        return user;
    }

    private void synchronizeUser(
            AuthUser user,
            AuthProperties.Bootstrap bootstrap,
            AuthOrganization organization
    ) {
        user.setOrganizationId(organization.getId());
        user.setFirstName(bootstrap.firstName().trim());
        user.setLastName(bootstrap.lastName().trim());
        user.setMiddleName(blankToNull(bootstrap.middleName()));
        user.setEmail(normalizeEmail(bootstrap.email()));

        if (user.getPasswordHash() == null
            || !passwordEncoder.matches(
                bootstrap.password(),
                user.getPasswordHash()
            )) {
            user.setPasswordHash(passwordEncoder.encode(bootstrap.password()));
        }

        user.setActive(true);
        user.setBlockedAt(null);
    }

    private void validate(AuthProperties.Bootstrap bootstrap) {
        if (bootstrap.email() == null || bootstrap.email().isBlank()
            || bootstrap.password() == null
            || bootstrap.password().length() < 8
            || bootstrap.firstName() == null
            || bootstrap.firstName().isBlank()
            || bootstrap.lastName() == null
            || bootstrap.lastName().isBlank()
            || bootstrap.organizationName() == null
            || bootstrap.organizationName().isBlank()) {
            throw AuthException.validation(
                "Параметры bootstrap SUPER_ADMIN заполнены некорректно"
            );
        }
    }

    private String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
