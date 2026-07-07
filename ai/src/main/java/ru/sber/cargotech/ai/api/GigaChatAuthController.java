package ru.sber.cargotech.ai.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sber.cargotech.ai.gigachat.GigaChatAuthService;

import java.time.Instant;
import java.util.Map;

@RestController
public class GigaChatAuthController {

    private final GigaChatAuthService authService;

    public GigaChatAuthController(GigaChatAuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/api/ai/gigachat/auth/check")
    public Map<String, Object> checkAuth() {
        boolean authorized = authService.isAuthorized();

        return Map.of(
                "authorized", authorized,
                "checkedAt", Instant.now().toString()
        );
    }
}