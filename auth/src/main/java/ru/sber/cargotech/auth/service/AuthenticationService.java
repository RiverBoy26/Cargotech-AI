package ru.sber.cargotech.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.auth.dto.*;
import ru.sber.cargotech.auth.entity.AuthUser;
import ru.sber.cargotech.auth.exception.AuthException;
import ru.sber.cargotech.auth.repository.AuthOutboxWriter;
import ru.sber.cargotech.auth.repository.AuthUserRepository;
import ru.sber.cargotech.auth.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {

    private final AuthUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessService accessService;
    private final TokenService tokenService;
    private final AuthOutboxWriter outboxWriter;

    @Transactional
    public TokenResponse login(
        LoginRequest request,
        RequestMetadata metadata
    ) {
        log.debug("Попытка входа: organizationContextProvided={}", request.organizationId() != null);

        AuthUser user = userRepository
            .findByEmailIgnoreCase(normalizeEmail(request.email()))
            .orElseThrow(this::invalidCredentials);

        if (!passwordEncoder.matches(
            request.password(),
            user.getPasswordHash()
        )) {
            throw invalidCredentials();
        }
        requireActiveUser(user);

        Set<String> roles = accessService.roleCodes(user.getId());
        UUID organizationContext = resolveOrganizationContext(
            user,
            roles,
            request.organizationId()
        );
        accessService.requireActiveOrganization(organizationContext);

        UserAccess access =
            accessService.load(user, organizationContext);

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        TokenPair tokens = tokenService.issuePair(
            access,
            metadata
        );

        outboxWriter.write(
            "USER",
            user.getId(),
            "AUTH_LOGIN_SUCCEEDED",
            organizationContext,
            user.getId(),
            Map.of("email", user.getEmail())
        );

        return tokenResponse(access, tokens);
    }

    public TokenResponse refresh(
        RefreshTokenRequest request,
        RequestMetadata metadata
    ) {
        log.debug("Запуск ротации refresh token");

        RefreshContext context =
            tokenService.consumeForRotation(request.refreshToken());

        AuthUser user = userRepository.findById(context.userId())
            .orElseThrow(() -> AuthException.unauthorized(
                "Refresh token недействителен"
            ));

        requireActiveUser(user);
        accessService.requireActiveOrganization(context.organizationId());

        Set<String> roles = accessService.roleCodes(user.getId());
        if (!roles.contains("SUPER_ADMIN")
            && !Objects.equals(
                user.getOrganizationId(),
                context.organizationId()
            )) {
            tokenService.revokeAll(user.getId());
            throw AuthException.unauthorized(
                "Контекст организации больше недоступен"
            );
        }

        UserAccess access =
            accessService.load(user, context.organizationId());
        TokenPair tokens = tokenService.issuePair(
            access,
            metadata
        );

        outboxWriter.write(
            "USER",
            user.getId(),
            "AUTH_TOKEN_REFRESHED",
            context.organizationId(),
            user.getId(),
            Map.of()
        );

        return tokenResponse(access, tokens);
    }

    public void logout(LogoutRequest request) {
        log.debug("Завершение пользовательской сессии");
        tokenService.revoke(request.refreshToken()).ifPresent(context ->
            outboxWriter.write(
                "USER",
                context.userId(),
                "AUTH_LOGOUT",
                context.organizationId(),
                context.userId(),
                Map.of()
            )
        );
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse me(CurrentUser principal) {
        log.debug("Получение профиля текущего пользователя: userId={}, organizationId={}", principal.userId(), principal.organizationId());

        AuthUser user = userRepository.findById(principal.userId())
            .orElseThrow(() -> AuthException.notFound(
                "Текущий пользователь не найден"
            ));

        requireActiveUser(user);
        accessService.requireActiveOrganization(principal.organizationId());
        UserAccess access = accessService.load(
            user,
            principal.organizationId()
        );

        return new CurrentUserResponse(
            user.getId(),
            principal.organizationId(),
            user.getFullName(),
            user.getEmail(),
            user.isActive(),
            user.getBlockedAt(),
            user.getLastLoginAt(),
            access.roles(),
            access.permissions()
        );
    }

    private UUID resolveOrganizationContext(
        AuthUser user,
        Set<String> roles,
        UUID requestedOrganizationId
    ) {
        if (roles.contains("SUPER_ADMIN")) {
            UUID context = requestedOrganizationId != null
                ? requestedOrganizationId
                : user.getOrganizationId();
            if (context == null) {
                throw AuthException.validation(
                    "Для SUPER_ADMIN необходимо передать organizationId"
                );
            }
            return context;
        }

        if (requestedOrganizationId != null
            && !requestedOrganizationId.equals(user.getOrganizationId())) {
            throw AuthException.forbidden(
                "Пользователь не имеет доступа к выбранной организации"
            );
        }
        return user.getOrganizationId();
    }

    private void requireActiveUser(AuthUser user) {
        if (!user.isActive() || user.getBlockedAt() != null) {
            throw AuthException.forbidden(
                "Учётная запись заблокирована"
            );
        }
    }

    private TokenResponse tokenResponse(
        UserAccess access,
        TokenPair tokens
    ) {
        return new TokenResponse(
            "Bearer",
            tokens.accessToken(),
            tokens.accessTokenExpiresAt(),
            tokens.refreshToken(),
            tokens.refreshTokenExpiresAt(),
            access.userId(),
            access.organizationId(),
            access.roles(),
            access.permissions()
        );
    }

    private AuthException invalidCredentials() {
        return AuthException.unauthorized("Неверный email или пароль");
    }

    private String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
