package ru.sber.cargotech.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.payment.dto.ClaimPaymentContextResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
public class ClaimClient {

    private final RestClient restClient;

    public ClaimClient(
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

    public ClaimPaymentContextResponse getPaymentContext(UUID claimId) {
        return restClient
                .get()
                .uri("/internal/api/v1/claims/{claimId}/payment-context", claimId)
                .retrieve()
                .body(ClaimPaymentContextResponse.class);
    }

    public List<ClaimPaymentContextResponse> findOpenByPayerInn(
            String payerInn
    ) {
        List<ClaimPaymentContextResponse> response = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/api/v1/claims/payment-contexts/by-payer-inn")
                        .queryParam("payerInn", payerInn)
                        .build()
                )
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        return response == null ? List.of() : response;
    }

    public List<ClaimPaymentContextResponse> findMentionedInPurpose(
            String purpose
    ) {
        List<ClaimPaymentContextResponse> response = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/api/v1/claims/payment-contexts/mentioned")
                        .queryParam("purpose", purpose)
                        .build()
                )
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        return response == null ? List.of() : response;
    }

    public void updateLastPaymentCheck(
            UUID claimId,
            UUID checkId
    ) {
        restClient
                .patch()
                .uri(
                        "/internal/api/v1/claims/{claimId}/last-payment-check",
                        claimId
                )
                .body(new UpdateLastPaymentCheckRequest(checkId))
                .retrieve()
                .toBodilessEntity();
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

    public record UpdateLastPaymentCheckRequest(
            UUID checkId
    ) {
    }
}
