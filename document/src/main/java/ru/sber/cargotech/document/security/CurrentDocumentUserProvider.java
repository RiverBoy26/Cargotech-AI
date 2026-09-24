package ru.sber.cargotech.document.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.document.exception.DocumentException;

import java.util.List;
import java.util.UUID;

@Component
public class CurrentDocumentUserProvider {

    public CurrentDocumentUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder
            .getContext()
            .getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken token)
            || !authentication.isAuthenticated()) {
            throw DocumentException.unauthorized(
                "Требуется действительный access token"
            );
        }

        Jwt jwt = token.getToken();

        return new CurrentDocumentUser(
            readUuid(jwt, "user_id"),
            readUuid(jwt, "organization_id"),
            jwt.getClaimAsString("email"),
            jwt.getClaimAsString("first_name"),
            jwt.getClaimAsString("last_name"),
            jwt.getClaimAsString("middle_name"),
            claimAsStringList(jwt, "roles"),
            claimAsStringList(jwt, "permissions")
        );
    }

    private UUID readUuid(Jwt jwt, String claimName) {
        String value = jwt.getClaimAsString(claimName);

        if (value == null || value.isBlank()) {
            throw DocumentException.unauthorized(
                "В access token отсутствует claim " + claimName
            );
        }

        return UUID.fromString(value);
    }

    private List<String> claimAsStringList(Jwt jwt, String claimName) {
        Object claim = jwt.getClaim(claimName);

        if (!(claim instanceof List<?> values)) {
            return List.of();
        }

        return values.stream()
            .map(String::valueOf)
            .toList();
    }
}
