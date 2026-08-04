package ru.sber.cargotech.auth.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.sber.cargotech.auth.exception.AuthException;

@Component
public class ClaimOrganizationProjectionClient {

    private final RestClient restClient;

    public ClaimOrganizationProjectionClient(
        @Value("${services.claim.base-url:http://localhost:8083}")
        String claimBaseUrl
    ) {
        this.restClient = RestClient.builder()
            .baseUrl(claimBaseUrl)
            .requestInterceptor((request, body, execution) -> {
                String token = currentBearerToken();
                if (token != null && !token.isBlank()) {
                    request.getHeaders().setBearerAuth(token);
                }
                if (!request.getHeaders().containsHeader(HttpHeaders.CONTENT_TYPE)) {
                    request.getHeaders().add(
                        HttpHeaders.CONTENT_TYPE,
                        "application/json"
                    );
                }
                return execution.execute(request, body);
            })
            .build();
    }

    public void synchronize(OrganizationProjectionRequest request) {
        try {
            restClient.post()
                .uri("/internal/api/v1/organization-projections")
                .body(request)
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw AuthException.conflict(
                "Claim-service не принял данные организации: "
                    + exception.getStatusCode()
            );
        } catch (ResourceAccessException exception) {
            throw AuthException.conflict(
                "Claim-service недоступен: организация не создана, "
                    + "чтобы не нарушить согласованность данных"
            );
        }
    }

    private static String currentBearerToken() {
        Authentication authentication = SecurityContextHolder
            .getContext()
            .getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt
            && authentication.isAuthenticated()) {
            return jwt.getToken().getTokenValue();
        }
        return null;
    }
}
