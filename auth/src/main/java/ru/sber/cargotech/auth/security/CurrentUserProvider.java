package ru.sber.cargotech.auth.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.auth.exception.AuthException;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Component
public class CurrentUserProvider {

    public CurrentUser getRequiredUser() {
        Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwt)
            || !authentication.isAuthenticated()) {
            throw AuthException.unauthorized("Пользователь не авторизован");
        }

        String userIdValue = jwt.getToken().getClaimAsString("user_id");
        if (userIdValue == null || userIdValue.isBlank()) {
            userIdValue = jwt.getToken().getSubject();
        }

        String organizationIdValue =
            jwt.getToken().getClaimAsString("organization_id");

        if (userIdValue == null || organizationIdValue == null) {
            throw AuthException.unauthorized(
                "JWT не содержит user_id/sub или organization_id"
            );
        }

        try {
            return new CurrentUser(
                UUID.fromString(userIdValue),
                UUID.fromString(organizationIdValue),
                stringSet(jwt.getToken().getClaim("roles")),
                stringSet(jwt.getToken().getClaim("permissions"))
            );
        } catch (IllegalArgumentException exception) {
            throw AuthException.unauthorized(
                "Идентификаторы в JWT имеют неверный формат"
            );
        }
    }

    private Set<String> stringSet(Object claim) {
        if (!(claim instanceof Collection<?> values)) {
            return Set.of();
        }

        Set<String> result = new TreeSet<>();
        values.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }
}
