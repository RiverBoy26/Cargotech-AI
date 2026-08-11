package ru.sber.cargotech.document.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.document.exception.DocumentException;

import java.util.UUID;

@Component
public class ClaimCalculationClient {

    private final RestClient restClient;

    public ClaimCalculationClient(
        @Value("${services.claim.base-url:http://localhost:8083}") String claimBaseUrl
    ) {
        this.restClient = RestClient.builder()
            .baseUrl(claimBaseUrl)
            .requestInterceptor((request, body, execution) -> {
                String token = currentBearerToken();
                if (token != null && !token.isBlank()) {
                    request.getHeaders().setBearerAuth(token);
                }
                return execution.execute(request, body);
            })
            .build();
    }

    public CalculationAttachment download(UUID claimId, String format) {
        try {
            ResponseEntity<byte[]> response = restClient.get()
                .uri("/api/v1/calculations/claim/{claimId}/export/{format}", claimId, format)
                .retrieve()
                .toEntity(byte[].class);
            byte[] content = response.getBody();
            if (content == null || content.length == 0) {
                throw DocumentException.unprocessable("Модуль claim вернул пустой файл расчёта");
            }
            String filename = response.getHeaders().getContentDisposition().getFilename();
            if (filename == null || filename.isBlank()) {
                filename = "claim_" + claimId + "_calculation." + format;
            }
            MediaType mediaType = response.getHeaders().getContentType();
            return new CalculationAttachment(
                filename,
                content,
                mediaType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mediaType.toString()
            );
        } catch (DocumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw DocumentException.unprocessable("Не удалось получить актуальный расчёт из модуля claim");
        }
    }

    private String currentBearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken().getTokenValue();
        }
        return null;
    }

    public record CalculationAttachment(
        String filename,
        byte[] content,
        String contentType
    ) {}
}
