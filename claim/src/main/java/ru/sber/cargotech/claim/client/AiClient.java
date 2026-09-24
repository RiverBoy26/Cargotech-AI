package ru.sber.cargotech.claim.client;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimRequest;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimResponse;
import ru.sber.cargotech.claim.exception.ClaimException;

@Component
@Slf4j
public class AiClient {
    private final RestClient restClient;
    private final AiClaimResponseDecoder responseDecoder;

    public AiClient(
        AiClientProperties properties,
        AiClaimResponseDecoder responseDecoder
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.responseDecoder = responseDecoder;
    }

    public AiGenerateClaimResponse generate(AiGenerateClaimRequest request) {
        return generate(request, null);
    }

    public AiGenerateClaimResponse generate(AiGenerateClaimRequest request, UUID actorUserId) {
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/api/ai/claims/generate");

            if (actorUserId != null) {
                requestSpec.header("X-CargoTech-Actor-User-Id", actorUserId.toString());
            }

            ResponseEntity<byte[]> aiHttpResponse = requestSpec
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (httpRequest, httpResponse) -> {
                        throw ClaimException.validation("AI-модуль отклонил данные претензии");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (httpRequest, httpResponse) -> {
                        throw ClaimException.conflict("AI-модуль временно недоступен");
                    })
                    .toEntity(byte[].class);

            return responseDecoder.decode(
                aiHttpResponse.getBody(),
                aiHttpResponse.getHeaders().getContentType(),
                aiHttpResponse.getHeaders().getContentDisposition().getFilename()
            );
        } catch (ClaimException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.error("Ошибка обращения к AI-модулю", exception);
            throw ClaimException.conflict("Не удалось выполнить генерацию претензии");
        }
    }
}
