package ru.sber.cargotech.auth.service;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.auth.entity.AuthUser;
import ru.sber.cargotech.auth.enums.SystemRole;
import ru.sber.cargotech.auth.exception.AuthException;
import ru.sber.cargotech.auth.security.CurrentUser;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class UserAccessPolicy {

    private static final Set<String> EXPEDITOR_ASSIGNABLE_ROLES =
        Set.of(SystemRole.ACCOUNTANT.name(), SystemRole.LAWYER.name());

    public UUID resolveTargetOrganization(
        CurrentUser actor,
        UUID requestedOrganizationId
    ) {
        if (actor.hasRole(SystemRole.SUPER_ADMIN.name())) {
            return requestedOrganizationId != null
                ? requestedOrganizationId
                : actor.organizationId();
        }

        requireExpeditorAdmin(actor);
        if (requestedOrganizationId != null
            && !requestedOrganizationId.equals(actor.organizationId())) {
            throw AuthException.forbidden(
                "Администратор экспедитора работает только в своей организации"
            );
        }
        return actor.organizationId();
    }

    public void checkTargetUser(
        CurrentUser actor,
        AuthUser target,
        Set<String> targetRoles
    ) {
        if (actor.hasRole(SystemRole.SUPER_ADMIN.name())) {
            return;
        }

        requireExpeditorAdmin(actor);
        if (!Objects.equals(
            actor.organizationId(),
            target.getOrganizationId()
        )) {
            throw AuthException.forbidden(
                "Нельзя управлять пользователем другой организации"
            );
        }

        if (targetRoles.contains(SystemRole.SUPER_ADMIN.name())
            || targetRoles.contains(SystemRole.EXPEDITOR_ADMIN.name())) {
            throw AuthException.forbidden(
                "Администратор экспедитора не может управлять администраторами"
            );
        }
    }

    public void checkRoleAssignment(
        CurrentUser actor,
        Set<String> requestedRoles
    ) {
        for (String role : requestedRoles) {
            try {
                SystemRole.valueOf(role);
            } catch (IllegalArgumentException exception) {
                throw AuthException.validation("Неизвестная роль: " + role);
            }
        }

        if (actor.hasRole(SystemRole.SUPER_ADMIN.name())) {
            return;
        }

        requireExpeditorAdmin(actor);
        if (!EXPEDITOR_ASSIGNABLE_ROLES.containsAll(requestedRoles)) {
            throw AuthException.forbidden(
                "Администратор экспедитора назначает только ACCOUNTANT и LAWYER"
            );
        }
    }

    public void checkCanChangeRoles(CurrentUser actor, UUID targetUserId) {
        if (actor.userId().equals(targetUserId)) {
            throw AuthException.forbidden(
                "Нельзя изменять собственные роли"
            );
        }
    }

    public void checkCanBlock(CurrentUser actor, UUID targetUserId) {
        if (actor.userId().equals(targetUserId)) {
            throw AuthException.forbidden(
                "Нельзя заблокировать собственную учётную запись"
            );
        }
    }

    private void requireExpeditorAdmin(CurrentUser actor) {
        if (!actor.hasRole(SystemRole.EXPEDITOR_ADMIN.name())) {
            throw AuthException.forbidden(
                "Операция доступна только администратору"
            );
        }
    }
}
