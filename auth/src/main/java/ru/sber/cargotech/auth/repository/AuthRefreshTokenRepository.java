package ru.sber.cargotech.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sber.cargotech.auth.entity.AuthRefreshToken;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AuthRefreshTokenRepository
    extends JpaRepository<AuthRefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from AuthRefreshToken token where token.tokenHash = :tokenHash")
    Optional<AuthRefreshToken> findForUpdateByTokenHash(
        @Param("tokenHash") String tokenHash
    );

    @Modifying
    @Query("""
        update AuthRefreshToken token
           set token.revokedAt = :revokedAt
         where token.userId = :userId
           and token.revokedAt is null
        """)
    int revokeAllActiveByUserId(
        @Param("userId") UUID userId,
        @Param("revokedAt") OffsetDateTime revokedAt
    );
}
