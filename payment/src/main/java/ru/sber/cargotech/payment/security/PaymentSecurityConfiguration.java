package ru.sber.cargotech.payment.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(PaymentSecurityProperties.class)
public class PaymentSecurityConfiguration {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Bean
    SecurityFilterChain paymentSecurityFilterChain(
            HttpSecurity http,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
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
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                )
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            PaymentSecurityProperties properties
    ) {
        String secretBase64 = properties.jwtSecretBase64();

        if (secretBase64 == null || secretBase64.isBlank()) {
            throw new IllegalStateException(
                    "payment.security.jwt-secret-base64 должен быть заполнен"
            );
        }

        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(secretBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "payment.security.jwt-secret-base64 должен быть корректной Base64-строкой",
                    exception
            );
        }

        if (secret.length < 32) {
            throw new IllegalStateException(
                    "JWT secret для HS256 должен содержать минимум 32 байта после Base64-декодирования"
            );
        }

        SecretKey secretKey = new SecretKeySpec(
                secret,
                HMAC_ALGORITHM
        );

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        if (
                properties.jwtIssuer() != null
                        && !properties.jwtIssuer().isBlank()
        ) {
            decoder.setJwtValidator(
                    JwtValidators.createDefaultWithIssuer(
                            properties.jwtIssuer()
                    )
            );
        }

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

            String principalName = jwt.getClaimAsString("user_id");

            if (principalName == null || principalName.isBlank()) {
                principalName = jwt.getSubject();
            }

            return new JwtAuthenticationToken(
                    jwt,
                    authorities,
                    principalName
            );
        };
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
}