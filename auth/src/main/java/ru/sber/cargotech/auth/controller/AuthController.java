package ru.sber.cargotech.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.auth.dto.CurrentUserResponse;
import ru.sber.cargotech.auth.dto.LoginRequest;
import ru.sber.cargotech.auth.dto.LogoutRequest;
import ru.sber.cargotech.auth.dto.RefreshTokenRequest;
import ru.sber.cargotech.auth.dto.RequestMetadata;
import ru.sber.cargotech.auth.dto.TokenResponse;
import ru.sber.cargotech.auth.security.CurrentUserProvider;
import ru.sber.cargotech.auth.service.AuthenticationService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final CurrentUserProvider currentUserProvider;

    public AuthController(
        AuthenticationService authenticationService,
        CurrentUserProvider currentUserProvider
    ) {
        this.authenticationService = authenticationService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/login")
    public TokenResponse login(
        @RequestBody @Valid LoginRequest request,
        HttpServletRequest servletRequest
    ) {
        return authenticationService.login(
            request,
            metadata(servletRequest)
        );
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(
        @RequestBody @Valid RefreshTokenRequest request,
        HttpServletRequest servletRequest
    ) {
        return authenticationService.refresh(
            request,
            metadata(servletRequest)
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @RequestBody @Valid LogoutRequest request
    ) {
        authenticationService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me() {
        return authenticationService.me(
            currentUserProvider.getRequiredUser()
        );
    }

    private RequestMetadata metadata(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = forwardedFor != null && !forwardedFor.isBlank()
            ? forwardedFor.split(",")[0].trim()
            : request.getRemoteAddr();

        return new RequestMetadata(
            ip,
            request.getHeader("User-Agent")
        );
    }
}
