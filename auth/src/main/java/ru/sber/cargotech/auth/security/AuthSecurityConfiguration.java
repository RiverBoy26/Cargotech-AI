package ru.sber.cargotech.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import ru.sber.cargotech.auth.config.AuthProperties;
import ru.sber.cargotech.auth.dto.ApiErrorResponse;
import ru.sber.cargotech.auth.exception.AuthException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Configuration
@EnableMethodSecurity
public class AuthSecurityConfiguration {

    private static final String JWT_SECRET_ALGORITHM = "HmacSHA256";

    @Bean
    PasswordEncoder passwordEncoder(AuthProperties properties) {
        int strength = properties.password().bcryptStrength();

        if (strength < 10 || strength > 15) {
            throw AuthException.validation(
                    "auth.password.bcrypt-strength должен быть в диапазоне 10..15"
            );
        }

        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    SecretKey jwtSecretKey(AuthProperties properties) {
        String secretBase64 = properties.jwt().secretBase64();

        if (secretBase64 == null || secretBase64.isBlank()) {
            throw AuthException.validation(
                    "auth.jwt.secret-base64 должен быть заполнен"
            );
        }

        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException exception) {
            throw AuthException.validation(
                    "auth.jwt.secret-base64 должен быть корректной Base64-строкой"
            );
        }

        if (secret.length < 32) {
            throw AuthException.validation(
                    "JWT secret для HS256 должен содержать минимум 32 байта после Base64-декодирования"
            );
        }

        return new SecretKeySpec(secret, JWT_SECRET_ALGORITHM);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey secretKey) {
        return NimbusJwtEncoder
                .withSecretKey(secretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            SecretKey secretKey,
            AuthProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        decoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer(
                        properties.jwt().issuer()
                )
        );

        return decoder;
    }

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();

            addAuthorities(
                    authorities,
                    jwt.getClaim("permissions"),
                    ""
            );

            addAuthorities(
                    authorities,
                    jwt.getClaim("roles"),
                    "ROLE_"
            );

            return new JwtAuthenticationToken(
                    jwt,
                    authorities,
                    jwt.getClaimAsString("user_id")
            );
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtConverter,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/actuator/health",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs.yaml",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        response,
                                        objectMapper,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "AUTH_UNAUTHORIZED",
                                        "Требуется действительный access token"
                                )
                        )
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        response,
                                        objectMapper,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "AUTH_FORBIDDEN",
                                        "Недостаточно прав"
                                )
                        )
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(
                                        response,
                                        objectMapper,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        "AUTH_UNAUTHORIZED",
                                        "Требуется действительный access token"
                                )
                        )
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        response,
                                        objectMapper,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        "AUTH_FORBIDDEN",
                                        "Недостаточно прав"
                                )
                        )
                )
                .build();
    }

    private static void addAuthorities(
            Collection<GrantedAuthority> target,
            Object claim,
            String prefix
    ) {
        if (!(claim instanceof Collection<?> values)) {
            return;
        }

        values.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .map(value -> new SimpleGrantedAuthority(prefix + value))
                .forEach(target::add);
    }

    private void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(
                        code,
                        message,
                        OffsetDateTime.now()
                )
        );
    }
}