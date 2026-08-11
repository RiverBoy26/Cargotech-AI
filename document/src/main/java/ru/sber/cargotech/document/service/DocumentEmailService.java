package ru.sber.cargotech.document.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.document.client.ClaimCalculationClient;
import ru.sber.cargotech.document.config.DocumentMailProperties;
import ru.sber.cargotech.document.dto.DocumentEmailDeliveryResponse;
import ru.sber.cargotech.document.dto.SendDocumentEmailRequest;
import ru.sber.cargotech.document.entity.Document;
import ru.sber.cargotech.document.entity.DocumentEmailDelivery;
import ru.sber.cargotech.document.entity.DocumentLink;
import ru.sber.cargotech.document.enums.DocumentEntityType;
import ru.sber.cargotech.document.enums.EmailDeliveryStatus;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.repository.DocumentEmailDeliveryRepository;
import ru.sber.cargotech.document.repository.DocumentLinkRepository;
import ru.sber.cargotech.document.security.CurrentDocumentUser;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentEmailService {

    private final JavaMailSender mailSender;
    private final DocumentMailProperties mailProperties;
    private final DocumentService documentService;
    private final DocumentEmailDeliveryRepository deliveryRepository;
    private final DocumentLinkRepository linkRepository;
    private final ClaimCalculationClient claimCalculationClient;

    public DocumentEmailService(
        JavaMailSender mailSender,
        DocumentMailProperties mailProperties,
        DocumentService documentService,
        DocumentEmailDeliveryRepository deliveryRepository,
        DocumentLinkRepository linkRepository,
        ClaimCalculationClient claimCalculationClient
    ) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
        this.documentService = documentService;
        this.deliveryRepository = deliveryRepository;
        this.linkRepository = linkRepository;
        this.claimCalculationClient = claimCalculationClient;
    }

    @PreAuthorize("hasAuthority('DOCUMENT_SEND')")
    public DocumentEmailDeliveryResponse send(
        UUID documentId,
        SendDocumentEmailRequest request,
        CurrentDocumentUser user
    ) {
        Document document = documentService.getActiveDocument(
            documentId,
            user.organizationId()
        );
        DocumentService.DocumentDownload attachment = documentService.download(
            documentId,
            user
        );

        DocumentEmailDelivery delivery = createDelivery(
            document,
            request,
            user
        );
        delivery.setStatus(EmailDeliveryStatus.SENDING);
        delivery.setAttemptCount(1);
        delivery = deliveryRepository.save(delivery);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                message,
                true,
                StandardCharsets.UTF_8.name()
            );
            helper.setFrom(
                mailProperties.from(),
                defaultValue(mailProperties.fromName(), "CargoTech")
            );
            helper.setTo(request.to());
            if (request.cc() != null && !request.cc().isBlank()) {
                helper.setCc(request.cc());
            }
            helper.setSubject(delivery.getSubject());
            helper.setText(defaultMessage(request.message()), false);
            helper.addAttachment(
                attachment.filename(),
                attachment.resource(),
                attachment.contentType()
            );
            addClaimCalculations(helper, document);

            mailSender.send(message);

            delivery.setStatus(EmailDeliveryStatus.SENT);
            delivery.setSentAt(OffsetDateTime.now());
            delivery.setErrorMessage(null);
            return toResponse(deliveryRepository.save(delivery));
        } catch (Exception exception) {
            delivery.setStatus(EmailDeliveryStatus.FAILED);
            delivery.setErrorMessage(safeError(exception));
            deliveryRepository.save(delivery);
            throw DocumentException.unprocessable(
                "Mailtrap не принял письмо; попытка сохранена в журнале доставки"
            );
        }
    }

    private void addClaimCalculations(MimeMessageHelper helper, Document document) throws Exception {
        UUID claimId = linkRepository.findAllByDocument_Id(document.getId()).stream()
            .filter(link -> link.getEntityType() == DocumentEntityType.CLAIM)
            .map(DocumentLink::getEntityId)
            .findFirst()
            .orElse(null);
        if (claimId == null) {
            return;
        }
        for (String format : List.of("pdf", "xlsx")) {
            var calculation = claimCalculationClient.download(claimId, format);
            helper.addAttachment(
                calculation.filename(),
                new ByteArrayResource(calculation.content()),
                calculation.contentType()
            );
        }
    }

    @PreAuthorize("hasAuthority('DOCUMENT_READ')")
    @Transactional(readOnly = true)
    public List<DocumentEmailDeliveryResponse> findAll(
        UUID documentId,
        CurrentDocumentUser user
    ) {
        documentService.getActiveDocument(documentId, user.organizationId());
        return deliveryRepository
            .findAllByDocument_IdAndOrganizationIdOrderByCreatedAtDesc(
                documentId,
                user.organizationId()
            )
            .stream()
            .map(this::toResponse)
            .toList();
    }

    private DocumentEmailDelivery createDelivery(
        Document document,
        SendDocumentEmailRequest request,
        CurrentDocumentUser user
    ) {
        DocumentEmailDelivery delivery = new DocumentEmailDelivery();
        delivery.setOrganizationId(user.organizationId());
        delivery.setDocument(document);
        delivery.setRecipient(request.to().trim().toLowerCase());
        delivery.setCc(normalizeEmail(request.cc()));
        delivery.setSubject(defaultSubject(request.subject(), document));
        delivery.setMessageBody(defaultMessage(request.message()));
        delivery.setStatus(EmailDeliveryStatus.PENDING);
        delivery.setSentBy(user.userId());
        return deliveryRepository.save(delivery);
    }

    private DocumentEmailDeliveryResponse toResponse(
        DocumentEmailDelivery delivery
    ) {
        return new DocumentEmailDeliveryResponse(
            delivery.getId(),
            delivery.getDocument().getId(),
            delivery.getRecipient(),
            delivery.getCc(),
            delivery.getSubject(),
            delivery.getStatus(),
            delivery.getAttemptCount(),
            delivery.getErrorMessage(),
            delivery.getSentBy(),
            delivery.getCreatedAt(),
            delivery.getSentAt(),
            delivery.getUpdatedAt()
        );
    }

    private String defaultSubject(String subject, Document document) {
        if (subject != null && !subject.isBlank()) {
            return subject.trim();
        }
        String number = document.getDocumentNumber() == null
            ? document.getId().toString()
            : document.getDocumentNumber();
        return "Претензия № " + number;
    }

    private String defaultMessage(String message) {
        return defaultValue(
            message,
            "Направляем претензию во вложении. Просим ознакомиться с документом."
        );
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank()
            ? null
            : email.trim().toLowerCase();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        String value = message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message;
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}
