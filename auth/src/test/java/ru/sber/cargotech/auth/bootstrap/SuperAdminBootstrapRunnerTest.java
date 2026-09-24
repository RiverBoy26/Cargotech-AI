package ru.sber.cargotech.auth.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.sber.cargotech.auth.config.AuthProperties;
import ru.sber.cargotech.auth.entity.AuthOrganization;
import ru.sber.cargotech.auth.entity.AuthRole;
import ru.sber.cargotech.auth.entity.AuthUser;
import ru.sber.cargotech.auth.enums.OrganizationStatus;
import ru.sber.cargotech.auth.repository.AuthOrganizationRepository;
import ru.sber.cargotech.auth.repository.AuthRoleRepository;
import ru.sber.cargotech.auth.repository.AuthUserRepository;
import ru.sber.cargotech.auth.repository.UserAccessRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminBootstrapRunnerTest {

    private static final String CONFIGURED_PASSWORD = "ConfiguredPassword123!";

    @Mock private AuthOrganizationRepository organizationRepository;
    @Mock private AuthUserRepository userRepository;
    @Mock private AuthRoleRepository roleRepository;
    @Mock private UserAccessRepository accessRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void restoresExistingBootstrapUserAndOrganization() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID superAdminRoleId = UUID.randomUUID();

        AuthProperties properties = properties(true);
        AuthOrganization organization = organization(organizationId);
        AuthUser user = blockedUser(userId);
        AuthRole superAdminRole = mock(AuthRole.class);

        when(organizationRepository.findByInn("7700000000"))
            .thenReturn(Optional.of(organization));
        when(organizationRepository.saveAndFlush(same(organization)))
            .thenReturn(organization);
        when(userRepository.findByEmailIgnoreCase("bootstrap@example.test"))
            .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CONFIGURED_PASSWORD, "old-hash"))
            .thenReturn(false);
        when(passwordEncoder.encode(CONFIGURED_PASSWORD))
            .thenReturn("new-hash");
        when(userRepository.saveAndFlush(same(user))).thenReturn(user);
        when(roleRepository.findByCode("SUPER_ADMIN"))
            .thenReturn(Optional.of(superAdminRole));
        when(superAdminRole.getId()).thenReturn(superAdminRoleId);
        when(accessRepository.findRoleCodes(userId)).thenReturn(Set.of());
        when(roleRepository.findAllByCodeIn(Set.of())).thenReturn(java.util.List.of());

        runner(properties).run(null);

        assertEquals(OrganizationStatus.ACTIVE, organization.getStatus());
        assertEquals(organizationId, user.getOrganizationId());
        assertEquals("Bootstrap", user.getFirstName());
        assertEquals("Administrator", user.getLastName());
        assertEquals("bootstrap@example.test", user.getEmail());
        assertEquals("new-hash", user.getPasswordHash());
        assertTrue(user.isActive());
        assertNull(user.getBlockedAt());
        verify(accessRepository).replaceRoles(
            userId,
            java.util.List.of(superAdminRoleId),
            null
        );
    }

    @Test
    void leavesExistingPasswordHashWhenConfiguredPasswordAlreadyMatches() {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        AuthProperties properties = properties(true);
        AuthOrganization organization = organization(organizationId);
        AuthUser user = blockedUser(userId);
        AuthRole superAdminRole = mock(AuthRole.class);

        when(organizationRepository.findByInn("7700000000"))
            .thenReturn(Optional.of(organization));
        when(organizationRepository.saveAndFlush(same(organization)))
            .thenReturn(organization);
        when(userRepository.findByEmailIgnoreCase("bootstrap@example.test"))
            .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CONFIGURED_PASSWORD, "old-hash"))
            .thenReturn(true);
        when(userRepository.saveAndFlush(same(user))).thenReturn(user);
        when(roleRepository.findByCode("SUPER_ADMIN"))
            .thenReturn(Optional.of(superAdminRole));
        when(accessRepository.findRoleCodes(userId)).thenReturn(Set.of("SUPER_ADMIN"));

        runner(properties).run(null);

        assertEquals("old-hash", user.getPasswordHash());
        assertTrue(user.isActive());
        assertNull(user.getBlockedAt());
        verify(passwordEncoder).matches(CONFIGURED_PASSWORD, "old-hash");
        org.mockito.Mockito.verify(passwordEncoder, org.mockito.Mockito.never())
            .encode(CONFIGURED_PASSWORD);
        org.mockito.Mockito.verify(accessRepository, org.mockito.Mockito.never())
            .replaceRoles(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.nullable(UUID.class)
            );
    }

    @Test
    void doesNothingWhenBootstrapIsDisabled() {
        runner(properties(false)).run(null);

        org.mockito.Mockito.verifyNoInteractions(
            organizationRepository,
            userRepository,
            roleRepository,
            accessRepository,
            passwordEncoder
        );
    }

    private SuperAdminBootstrapRunner runner(AuthProperties properties) {
        return new SuperAdminBootstrapRunner(
            properties,
            organizationRepository,
            userRepository,
            roleRepository,
            accessRepository,
            passwordEncoder
        );
    }

    private AuthProperties properties(boolean enabled) {
        return new AuthProperties(
            null,
            null,
            new AuthProperties.Bootstrap(
                enabled,
                "bootstrap@example.test",
                CONFIGURED_PASSWORD,
                "Bootstrap",
                "Administrator",
                "",
                "CargoTech",
                "7700000000"
            )
        );
    }

    private AuthOrganization organization(UUID id) {
        AuthOrganization organization = new AuthOrganization();
        organization.setId(id);
        organization.setName("CargoTech");
        organization.setInn("7700000000");
        organization.setStatus(OrganizationStatus.BLOCKED);
        return organization;
    }

    private AuthUser blockedUser(UUID id) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setOrganizationId(UUID.randomUUID());
        user.setFirstName("Old");
        user.setLastName("Name");
        user.setEmail("bootstrap@example.test");
        user.setPasswordHash("old-hash");
        user.setActive(false);
        user.setBlockedAt(OffsetDateTime.now());
        return user;
    }
}
