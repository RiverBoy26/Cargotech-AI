package ru.sber.cargotech.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.sber.cargotech.payment.exception.PaymentException;
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

                    if (!request.getHeaders()
                            .containsHeader(HttpHeaders.CONTENT_TYPE)) {

                        request.getHeaders().add(
                                HttpHeaders.CONTENT_TYPE,
                                "application/json"
                        );
                    }

                    return execution.execute(request, body);
                })
                .build();
    }

    public ClaimPaymentContextResponse getPaymentContext(
            UUID claimId
    ) {
        try {
            ClaimPaymentContextResponse response = restClient
                    .get()
                    .uri(
                            "/internal/api/v1/claims/{claimId}/payment-context",
                            claimId
                    )
                    .retrieve()
                    .body(ClaimPaymentContextResponse.class);

            if (response == null) {
                throw PaymentException.notFound(
                        "Claim вернул пустой ответ для претензии %s"
                                .formatted(claimId)
                );
            }

            return response;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw PaymentException.notFound(
                        "Претензия %s не найдена".formatted(claimId)
                );
            }

            throw PaymentException.conflict(
                    "Ошибка обращения к claim при получении претензии: "
                            + exception.getStatusCode()
            );
        } catch (ResourceAccessException exception) {
            throw PaymentException.conflict(
                    "Модуль claim недоступен"
            );
        }
    }

    public List<ClaimPaymentContextResponse> findOpenByPayerInn(
            String payerInn
    ) {
        if (payerInn == null || payerInn.isBlank()) {
            return List.of();
        }

        try {
            List<ClaimPaymentContextResponse> response = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(
                                    "/internal/api/v1/claims/"
                                            + "payment-contexts/by-payer-inn"
                            )
                            .queryParam("payerInn", payerInn)
                            .build()
                    )
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            return response == null
                    ? List.of()
                    : response;
        } catch (RestClientResponseException exception) {
            throw PaymentException.conflict(
                    "Ошибка обращения к claim при поиске претензий "
                            + "по ИНН: "
                            + exception.getStatusCode()
            );
        } catch (ResourceAccessException exception) {
            throw PaymentException.conflict(
                    "Модуль claim недоступен"
            );
        }
    }

    public List<ClaimPaymentContextResponse> findMentionedInPurpose(
            String purpose
    ) {
        if (purpose == null || purpose.isBlank()) {
            return List.of();
        }

        try {
            List<ClaimPaymentContextResponse> response = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(
                                    "/internal/api/v1/claims/"
                                            + "payment-contexts/mentioned"
                            )
                            .queryParam("purpose", purpose)
                            .build()
                    )
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            return response == null
                    ? List.of()
                    : response;
        } catch (RestClientResponseException exception) {
            throw PaymentException.conflict(
                    "Ошибка обращения к claim при поиске претензий "
                            + "в назначении платежа: "
                            + exception.getStatusCode()
            );
        } catch (ResourceAccessException exception) {
            throw PaymentException.conflict(
                    "Модуль claim недоступен"
            );
        }
    }

    public void updateLastPaymentCheck(
            UUID claimId,
            UUID checkId,
            BigDecimal remainingPrincipalAmount,
            BigDecimal remainingPenaltyAmount
    ) {
        try {
            restClient
                    .patch()
                    .uri(
                            "/internal/api/v1/claims/"
                                    + "{claimId}/last-payment-check",
                            claimId
                    )
                    .body(new UpdateLastPaymentCheckRequest(
                            checkId,
                            remainingPrincipalAmount,
                            remainingPenaltyAmount
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw PaymentException.notFound(
                        "Претензия %s не найдена".formatted(claimId)
                );
            }

            throw PaymentException.conflict(
                    "Ошибка обновления последней проверки оплаты "
                            + "в claim: "
                            + exception.getStatusCode()
            );
        } catch (ResourceAccessException exception) {
            throw PaymentException.conflict(
                    "Модуль claim недоступен"
            );
        }
    }

    public void markPaid(UUID claimId) {
        try {
            restClient
                .post()
                .uri(
                    "/internal/api/v1/claims/{claimId}/mark-paid",
                    claimId
                )
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw PaymentException.conflict(
                "Оплата зафиксирована, но claim не принял статус PAID: "
                    + exception.getStatusCode()
            );
        } catch (ResourceAccessException exception) {
            throw PaymentException.conflict(
                "Модуль claim недоступен для фиксации статуса PAID"
            );
        }
    }

    public void syncPaymentState(UUID claimId) {
        syncPaymentState(claimId, null);
    }

    public void syncPaymentState(UUID claimId, String reason) {
        try {
            restClient
                .post()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path(
                        "/internal/api/v1/claims/{claimId}/sync-payment-state"
                    );
                    if (reason != null && !reason.isBlank()) {
                        builder.queryParam("reason", reason);
                    }
                    return builder.build(claimId);
                })
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw PaymentException.conflict(
                "Платёж зафиксирован, но claim не выполнил перерасчёт: "
                    + exception.getStatusCode()
            );
        } catch (ResourceAccessException exception) {
            throw PaymentException.conflict(
                "Модуль claim недоступен для перерасчёта после платежа"
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

    public record UpdateLastPaymentCheckRequest(
            UUID checkId,
            BigDecimal remainingPrincipalAmount,
            BigDecimal remainingPenaltyAmount
    ) {
    }
}
