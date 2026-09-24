package ru.sber.cargotech.claim.client;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.claim.dto.PaymentClaimStateResponse;
import ru.sber.cargotech.claim.dto.PaymentPreflightResponse;
import ru.sber.cargotech.claim.exception.ClaimException;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(PaymentClientProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
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

    public PaymentClaimStateResponse getClaimPaymentState(UUID claimId) {
        return restClient
                .get()
                .uri("/api/v1/payments/claims/{claimId}", claimId)
                .retrieve()
                .body(PaymentClaimStateResponse.class);
    }

    public PaymentPreflightResponse preflightCheck(
            UUID claimId,
            String comment
    ) {
        return restClient
                .post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/payments/claims/{claimId}/preflight-check")
                        .queryParam("comment", comment)
                        .build(claimId)
                )
                .retrieve()
                .body(PaymentPreflightResponse.class);
    }

    public void deleteForClaimAndShipment(UUID claimId, UUID shipmentId) {
        try {
            restClient
                .delete()
                .uri(
                    "/internal/api/v1/payments/by-claim/{claimId}/shipment/{shipmentId}",
                    claimId,
                    shipmentId
                )
                .retrieve()
                .toBodilessEntity();
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            throw ClaimException.conflict(
                "Не удалось удалить связанные платежи: HTTP "
                    + exception.getStatusCode().value()
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

    public PaymentStateResponse getPaymentState(
            UUID claimId,
            UUID shipmentId,
            BigDecimal serviceAmount
    ) {
        PaymentStateResponse response = restClient
                .post()
                .uri("/internal/api/v1/payments/payment-state")
                .body(new PaymentStateRequest(
                        claimId,
                        shipmentId,
                        serviceAmount
                ))
                .retrieve()
                .body(PaymentStateResponse.class);

        if (response == null) {
            throw ClaimException.conflict(
                    "Payment вернул пустой ответ"
            );
        }

        return response;
    }

    public record PaymentStateRequest(
            UUID claimId,
            UUID shipmentId,
            BigDecimal serviceAmount
    ) {
    }

    public record PaymentStateResponse(
            UUID claimId,
            UUID shipmentId,
            BigDecimal serviceAmount,
            BigDecimal paidAmount,
            BigDecimal remainingAmount,
            String paymentStatus,
            List<PaymentAllocationResponse> allocations
    ) {
    }

    public record PaymentAllocationResponse(
            LocalDate paymentDate,
            BigDecimal amount
    ) {
    }
}
