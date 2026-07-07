package ru.sber.cargotech.ai.gigachat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatOAuthResponse;

import java.time.Instant;
import java.util.UUID;

@Service
public class GigaChatAuthService {

    private final GigaChatProperties properties;
    private final RestClient restClient;

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public GigaChatAuthService(GigaChatProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    public synchronized String getAccessToken() {
        if (isCachedTokenValid()) {
            return cachedToken;
        }

        validateConfig();

        var body = new LinkedMultiValueMap<String, String>();
        body.add("scope", properties.getScope());

        GigaChatOAuthResponse response = restClient.post()
                .uri(properties.getAuthUrl())
                .header(HttpHeaders.AUTHORIZATION, "Basic " + properties.getAuthKey())
                .header("RqUID", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(GigaChatOAuthResponse.class);

        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new IllegalStateException("GigaChat OAuth returned empty access_token");
        }

        this.cachedToken = response.getAccessToken();
        this.expiresAt = parseExpiresAt(response.getExpiresAt());

        return cachedToken;
    }

    public boolean isAuthorized() {
        String token = getAccessToken();
        return token != null && !token.isBlank();
    }

    private boolean isCachedTokenValid() {
        Instant refreshBefore = expiresAt.minusSeconds(properties.getTokenRefreshSkewSeconds());
        return cachedToken != null && Instant.now().isBefore(refreshBefore);
    }

    private Instant parseExpiresAt(Long expiresAtValue) {
        if (expiresAtValue == null) {
            return Instant.now().plusSeconds(25 * 60);
        }

        // GigaChat обычно отдаёт expires_at в миллисекундах Unix time.
        // Если внезапно пришли секунды, тоже обработаем.
        if (expiresAtValue > 10_000_000_000L) {
            return Instant.ofEpochMilli(expiresAtValue);
        }

        return Instant.ofEpochSecond(expiresAtValue);
    }

    private void validateConfig() {
        if (properties.getAuthKey() == null || properties.getAuthKey().isBlank()) {
            throw new IllegalStateException("GIGACHAT_AUTH_KEY is not configured");
        }

        if (properties.getScope() == null || properties.getScope().isBlank()) {
            throw new IllegalStateException("GIGACHAT_SCOPE is not configured");
        }
    }
}