package ru.sber.cargotech.payment.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.payment.exception.PaymentException;

import java.util.UUID;

@Component
public class CurrentPaymentUserProvider {
    public CurrentPaymentUser getRequiredUser() {
        Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwt)
            || !authentication.isAuthenticated()) {
            throw PaymentException.forbidden("Пользователь не авторизован");
        }

        String userId = jwt.getToken().getClaimAsString("user_id");
        if (userId == null || userId.isBlank()) {
            userId = jwt.getToken().getSubject();
        }

        String organizationId =
            jwt.getToken().getClaimAsString("organization_id");

        if (userId == null || organizationId == null) {
            throw PaymentException.forbidden(
                "JWT должен содержать user_id/sub и organization_id"
            );
        }

        try {
            return new CurrentPaymentUser(
                UUID.fromString(userId),
                UUID.fromString(organizationId)
            );
        } catch (IllegalArgumentException exception) {
            throw PaymentException.forbidden(
                "user_id и organization_id должны иметь формат UUID"
            );
        }
    }
}
