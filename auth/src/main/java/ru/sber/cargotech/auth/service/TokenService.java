package ru.sber.cargotech.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.auth.config.AuthProperties;
import ru.sber.cargotech.auth.dto.RefreshContext;
import ru.sber.cargotech.auth.dto.RequestMetadata;
import ru.sber.cargotech.auth.dto.TokenPair;
import ru.sber.cargotech.auth.dto.UserAccess;
import ru.sber.cargotech.auth.entity.AuthRefreshToken;
import ru.sber.cargotech.auth.exception.AuthException;
import ru.sber.cargotech.auth.repository.AuthRefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public TokenPair issuePair(
            UserAccess access,
            RequestMetadata metadata
    ) {
        log.debug("Выпуск пары токенов: userId={}, organizationId={}, rolesCount={}, permissionsCount={}", access.userId(), access.organizationId(), access.roles().size(), access.permissions().size());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        OffsetDateTime accessExpiresAt = now.plus(
                properties.jwt().accessTokenTtl()
        );

        OffsetDateTime refreshExpiresAt = now.plus(
                properties.jwt().refreshTokenTtl()
        );

        String accessToken = issueAccessToken(
                access,
                now,
                accessExpiresAt
        );

        String refreshToken = generateRefreshToken(
                access.organizationId()
        );

        AuthRefreshToken entity = new AuthRefreshToken();
        entity.setUserId(access.userId());
        entity.setTokenHash(hash(refreshToken));
        entity.setExpiresAt(refreshExpiresAt);
        entity.setCreatedAt(now);
        entity.setCreatedIp(trim(metadata.ipAddress(), 64));
        entity.setUserAgent(trim(metadata.userAgent(), 512));

        refreshTokenRepository.save(entity);

        return new TokenPair(
                accessToken,
                accessExpiresAt,
                refreshToken,
                refreshExpiresAt
        );
    }

    @Transactional(noRollbackFor = AuthException.class)
    public RefreshContext consumeForRotation(String rawToken) {
        log.debug("Проверка refresh token для ротации");

        AuthRefreshToken entity = refreshTokenRepository
                .findForUpdateByTokenHash(hash(rawToken))
                .orElseThrow(() -> AuthException.unauthorized(
                        "Refresh token недействителен"
                ));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (entity.getRevokedAt() != null) {
            refreshTokenRepository.revokeAllActiveByUserId(
                    entity.getUserId(),
                    now
            );

            throw AuthException.unauthorized(
                    "Обнаружено повторное использование refresh token"
            );
        }

        if (!entity.getExpiresAt().isAfter(now)) {
            entity.setRevokedAt(now);
            refreshTokenRepository.save(entity);

            throw AuthException.unauthorized(
                    "Refresh token истёк"
            );
        }

        UUID organizationId = extractOrganizationId(rawToken);

        entity.setRevokedAt(now);
        refreshTokenRepository.save(entity);

        return new RefreshContext(
                entity.getUserId(),
                organizationId
        );
    }

    @Transactional
    public Optional<RefreshContext> revoke(String rawToken) {
        log.debug("Отзыв refresh token");

        return refreshTokenRepository
                .findForUpdateByTokenHash(hash(rawToken))
                .map(entity -> {
                    if (entity.getRevokedAt() == null) {
                        entity.setRevokedAt(
                                OffsetDateTime.now(ZoneOffset.UTC)
                        );

                        refreshTokenRepository.save(entity);
                    }

                    return new RefreshContext(
                            entity.getUserId(),
                            extractOrganizationId(rawToken)
                    );
                });
    }

    @Transactional
    public void revokeAll(UUID userId) {
        log.debug("Отзыв всех активных refresh token: userId={}", userId);

        refreshTokenRepository.revokeAllActiveByUserId(
                userId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private String issueAccessToken(
            UserAccess access,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt
    ) {
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(issuedAt.toInstant())
                .expiresAt(expiresAt.toInstant())
                .subject(access.userId().toString())
                .claim("user_id", access.userId().toString())
                .claim("organization_id", access.organizationId().toString())
                .claim("first_name", access.firstName())
                .claim("last_name", access.lastName())
                .claim("email", access.email())
                .claim("roles", access.roles())
                .claim("permissions", access.permissions());

        if (access.middleName() != null) {
            claimsBuilder.claim("middle_name", access.middleName());
        }

        JwtClaimsSet claims = claimsBuilder.build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        return jwtEncoder
                .encode(
                        JwtEncoderParameters.from(
                                header,
                                claims
                        )
                )
                .getTokenValue();
    }

    private String generateRefreshToken(UUID organizationId) {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);

        String secret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(random);

        return organizationId + "." + secret;
    }

    private UUID extractOrganizationId(String rawToken) {
        int separator = rawToken.indexOf('.');

        if (separator <= 0) {
            throw AuthException.unauthorized(
                    "Refresh token имеет неверный формат"
            );
        }

        try {
            return UUID.fromString(
                    rawToken.substring(0, separator)
            );
        } catch (IllegalArgumentException exception) {
            throw AuthException.unauthorized(
                    "Refresh token имеет неверный формат"
            );
        }
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            rawToken.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 недоступен",
                    exception
            );
        }
    }

    private String trim(
            String value,
            int length
    ) {
        if (value == null || value.length() <= length) {
            return value;
        }

        return value.substring(0, length);
    }
}
