package ru.sber.cargotech.claim.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.claim.exception.ClaimException;

import java.util.List;
import java.util.UUID;

@Component
public class CurrentClaimUserProvider {
    public CurrentClaimUser getRequiredUser() {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !authentication.isAuthenticated()) {
            throw ClaimException.forbidden("Пользователь не авторизован");
        }

        String userId = jwt.getToken().getClaimAsString("user_id");
        if (userId == null || userId.isBlank()) {
            userId = jwt.getToken().getSubject();
        }
        String organizationId = jwt.getToken().getClaimAsString("organization_id");

        if (userId == null || organizationId == null) {
            throw ClaimException.forbidden(
                    "JWT должен содержать user_id/sub и organization_id"
            );
        }

        try {
            List<String> roles = jwt.getToken().getClaimAsStringList("roles");
            return new CurrentClaimUser(
                    UUID.fromString(userId),
                    UUID.fromString(organizationId),
                    jwt.getToken().getClaimAsString("first_name"),
                    jwt.getToken().getClaimAsString("last_name"),
                    jwt.getToken().getClaimAsString("middle_name"),
                    roles == null ? List.of() : roles
            );
        } catch (IllegalArgumentException exception) {
            throw ClaimException.forbidden(
                    "user_id и organization_id должны иметь формат UUID"
            );
        }
    }
}
