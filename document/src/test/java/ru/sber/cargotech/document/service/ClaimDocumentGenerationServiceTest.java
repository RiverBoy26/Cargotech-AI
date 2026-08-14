package ru.sber.cargotech.document.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.document.client.ClaimCalculationClient;
import ru.sber.cargotech.document.dto.GenerateClaimDocumentRequest;
import ru.sber.cargotech.document.enums.GeneratedDocumentType;
import ru.sber.cargotech.document.enums.GenerationStatus;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.repository.DocumentGenerationLogRepository;
import ru.sber.cargotech.document.repository.DocumentLinkRepository;
import ru.sber.cargotech.document.security.CurrentDocumentUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimDocumentGenerationServiceTest {

    @Mock private ClaimTemplateService templateService;
    @Mock private ClaimDocxRenderer docxRenderer;
    @Mock private ClaimPdfRenderer pdfRenderer;
    @Mock private DocumentService documentService;
    @Mock private DocumentLinkRepository linkRepository;
    @Mock private DocumentGenerationLogRepository generationLogRepository;
    @Mock private ClaimCalculationClient calculationClient;

    @Test
    void rejectsSecondDocumentOfSameFormatForSameClaimVersion() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID claimVersionId = UUID.randomUUID();
        CurrentDocumentUser user = new CurrentDocumentUser(
            UUID.randomUUID(), organizationId, "lawyer@example.test",
            "Иван", "Юристов", null, List.of("LAWYER"),
            List.of("DOCUMENT_GENERATE")
        );
        GenerateClaimDocumentRequest request = new GenerateClaimDocumentRequest(
            null, null, null, claimId, claimVersionId,
            GeneratedDocumentType.CLAIM_PDF, "CLM-1", null,
            "Версия претензии: 5", "Текст претензии", Map.of()
        );
        when(generationLogRepository
            .existsByOrganizationIdAndClaimIdAndClaimVersionIdAndOutputTypeAndStatusIn(
                eq(organizationId),
                eq(claimId),
                eq(claimVersionId),
                any(GeneratedDocumentType.class),
                eq(List.of(GenerationStatus.PROCESSING, GenerationStatus.COMPLETED))
            )).thenAnswer(invocation ->
                invocation.getArgument(3) == GeneratedDocumentType.CLAIM_PDF
            );

        ClaimDocumentGenerationService service = new ClaimDocumentGenerationService(
            templateService,
            docxRenderer,
            pdfRenderer,
            documentService,
            linkRepository,
            generationLogRepository,
            calculationClient
        );

        assertThatThrownBy(() -> service.generateClaimDocument(request, user))
            .isInstanceOfSatisfying(DocumentException.class, exception -> {
                assertThat(exception.status().value()).isEqualTo(409);
                assertThat(exception.getMessage()).contains("уже сформирован");
            });
    }

    @Test
    void allowsDocxWhenPdfAlreadyExistsForSameClaimVersion() {
        UUID organizationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID claimVersionId = UUID.randomUUID();
        CurrentDocumentUser user = new CurrentDocumentUser(
            UUID.randomUUID(), organizationId, "lawyer@example.test",
            "Иван", "Юристов", null, List.of("LAWYER"),
            List.of("DOCUMENT_GENERATE")
        );
        GenerateClaimDocumentRequest request = new GenerateClaimDocumentRequest(
            null, null, null, claimId, claimVersionId,
            GeneratedDocumentType.CLAIM_DOCX, "CLM-1", null,
            "Версия претензии: 5", "Текст претензии", Map.of()
        );
        when(generationLogRepository
            .existsByOrganizationIdAndClaimIdAndClaimVersionIdAndOutputTypeAndStatusIn(
                eq(organizationId),
                eq(claimId),
                eq(claimVersionId),
                any(GeneratedDocumentType.class),
                eq(List.of(GenerationStatus.PROCESSING, GenerationStatus.COMPLETED))
            )).thenAnswer(invocation ->
                invocation.getArgument(3) == GeneratedDocumentType.CLAIM_PDF
            );
        when(generationLogRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(docxRenderer.render(anyString(), anyMap()))
            .thenThrow(new IllegalStateException("DOCX renderer reached"));

        ClaimDocumentGenerationService service = new ClaimDocumentGenerationService(
            templateService,
            docxRenderer,
            pdfRenderer,
            documentService,
            linkRepository,
            generationLogRepository,
            calculationClient
        );

        assertThatThrownBy(() -> service.generateClaimDocument(request, user))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("DOCX renderer reached");
    }
}
