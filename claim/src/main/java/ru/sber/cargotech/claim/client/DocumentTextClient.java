package ru.sber.cargotech.claim.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.claim.exception.ClaimException;

import java.util.UUID;
import java.time.LocalDate;
import java.util.List;

@Component
public class DocumentTextClient {

    private final RestClient restClient;

    public DocumentTextClient(DocumentTextClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader("X-Internal-Api-Key", properties.internalApiKey())
            .build();
    }

    public DocumentTextResponse getText(UUID organizationId, UUID documentId) {
        DocumentTextResponse response = restClient.get()
            .uri(
                "/internal/api/v1/documents/{documentId}/text?organizationId={organizationId}",
                documentId, organizationId
            )
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, error) -> {
                throw ClaimException.conflict("Не удалось получить текст загруженного договора");
            })
            .body(DocumentTextResponse.class);
        if (response == null || response.text() == null || response.text().isBlank()) {
            throw ClaimException.validation("В загруженном договоре не найден текст");
        }
        return response;
    }

    public ClaimDocumentReadinessResponse getClaimReadiness(UUID claimId) {
        ClaimDocumentReadinessResponse response = restClient.get()
            .uri("/internal/api/v1/documents/claims/{claimId}/readiness", claimId)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, error) -> {
                throw ClaimException.conflict("Не удалось проверить документы претензии");
            })
            .body(ClaimDocumentReadinessResponse.class);
        if (response == null) {
            throw ClaimException.conflict("Сервис документов не вернул результат проверки");
        }
        return response;
    }

    public record DocumentTextResponse(
        UUID documentId,
        String text,
        String extractionMethod,
        Integer pageCount
    ) {}

    public record ClaimDocumentReadinessResponse(
        UUID claimId,
        boolean generatedClaimPresent,
        boolean calculationPdfPresent,
        boolean calculationXlsxPresent,
        int attachmentCount,
        List<DocumentReference> documents
    ) {}

    public record DocumentReference(
        UUID documentId,
        String documentType,
        String linkType,
        String documentNumber,
        LocalDate documentDate
    ) {}
}
