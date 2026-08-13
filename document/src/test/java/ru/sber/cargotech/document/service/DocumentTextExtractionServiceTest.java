package ru.sber.cargotech.document.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.document.entity.Document;
import ru.sber.cargotech.document.entity.DocumentText;
import ru.sber.cargotech.document.enums.DocumentStatus;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.repository.DocumentRepository;
import ru.sber.cargotech.document.repository.DocumentTextRepository;
import ru.sber.cargotech.document.storage.LocalDocumentStorageService;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTextExtractionServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentTextRepository textRepository;
    @Mock private LocalDocumentStorageService storageService;

    @Test
    void internalTextReadRequiresMatchingOrganization() {
        UUID organizationId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Document document = new Document();
        document.setId(documentId);
        document.setOrganizationId(organizationId);
        document.setStatus(DocumentStatus.ACTIVE);
        DocumentText text = new DocumentText();
        text.setDocument(document);
        text.setTextContent("8.2. Оплата производится в течение 30 дней.");
        text.setExtractionMethod("APACHE_POI");
        text.setPageCount(1);

        when(documentRepository.findByIdAndOrganizationIdAndStatusNot(
            documentId, organizationId, DocumentStatus.DELETED
        )).thenReturn(Optional.of(document));
        when(textRepository.findByDocument_Id(documentId)).thenReturn(Optional.of(text));

        var response = service().getInternal(organizationId, documentId);

        assertThat(response.documentId()).isEqualTo(documentId);
        assertThat(response.text()).contains("Оплата производится");
    }

    @Test
    void internalTextReadDoesNotRevealForeignTenantDocument() {
        UUID requestedOrganizationId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndOrganizationIdAndStatusNot(
            documentId, requestedOrganizationId, DocumentStatus.DELETED
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getInternal(requestedOrganizationId, documentId))
            .isInstanceOf(DocumentException.class)
            .satisfies(exception -> assertThat(((DocumentException) exception).status().value()).isEqualTo(404));

        verifyNoInteractions(textRepository);
    }

    private DocumentTextExtractionService service() {
        return new DocumentTextExtractionService(documentRepository, textRepository, storageService);
    }
}
