package ru.sber.cargotech.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.sber.cargotech.payment.dto.PaymentImportResponse;
import ru.sber.cargotech.payment.entity.Payment;
import ru.sber.cargotech.payment.entity.PaymentImport;
import ru.sber.cargotech.payment.enums.PaymentImportStatus;
import ru.sber.cargotech.payment.enums.PaymentSourceSystem;
import ru.sber.cargotech.payment.enums.PaymentStatus;
import ru.sber.cargotech.payment.exception.PaymentException;
import ru.sber.cargotech.payment.parser.PaymentSpreadsheetParser;
import ru.sber.cargotech.payment.repository.PaymentImportRepository;
import ru.sber.cargotech.payment.repository.PaymentOutboxWriter;
import ru.sber.cargotech.payment.repository.PaymentRepository;
import ru.sber.cargotech.payment.security.CurrentPaymentUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentImportService {

    private final PaymentSpreadsheetParser parser;
    private final PaymentImportRepository importRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentOutboxWriter outboxWriter;

    @Transactional
    public PaymentImportResponse importFromOneC(
        MultipartFile file,
        CurrentPaymentUser user
    ) {
        log.debug("Импорт из 1С: organizationId={}, userId={}, fileName={}, sizeBytes={}", user.organizationId(), user.userId(), file.getOriginalFilename(), file.getSize());

        return importPayments(file, PaymentSourceSystem.ONE_C, user);
    }

    @Transactional
    public PaymentImportResponse importBankStatement(
        MultipartFile file,
        CurrentPaymentUser user
    ) {
        log.debug("Импорт банковской выписки: organizationId={}, userId={}, fileName={}, sizeBytes={}", user.organizationId(), user.userId(), file.getOriginalFilename(), file.getSize());

        return importPayments(file, PaymentSourceSystem.BANK_STATEMENT, user);
    }

    private PaymentImportResponse importPayments(
        MultipartFile file,
        PaymentSourceSystem sourceSystem,
        CurrentPaymentUser user
    ) {
        log.debug("Обработка файла импорта: sourceSystem={}, organizationId={}, fileName={}", sourceSystem, user.organizationId(), file.getOriginalFilename());

        if (file == null || file.isEmpty()) {
            throw PaymentException.unprocessable("Файл импорта пуст");
        }

        OffsetDateTime now = OffsetDateTime.now();
        PaymentImport batch = new PaymentImport();
        batch.setOrganizationId(user.organizationId());
        batch.setSourceSystem(sourceSystem);
        batch.setStatus(PaymentImportStatus.PROCESSING);
        batch.setTotalRows(0);
        batch.setImportedRows(0);
        batch.setFailedRows(0);
        batch.setStartedAt(now);
        batch.setCreatedBy(user.userId());
        batch.setCreatedAt(now);
        batch.setErrorDetails(List.of());
        batch = importRepository.save(batch);

        PaymentSpreadsheetParser.ParseResult parsed = parser.parse(file);
        int imported = 0;
        int duplicates = 0;

        for (PaymentSpreadsheetParser.PaymentRow row : parsed.rows()) {
            if (isDuplicate(row, sourceSystem, user.organizationId())) {
                duplicates++;
                continue;
            }
            paymentRepository.save(toPayment(
                row,
                sourceSystem,
                batch.getId(),
                user.organizationId()
            ));
            imported++;
        }

        PaymentImportStatus status = resolveStatus(
            imported,
            parsed.errors().size()
        );

        batch.setStatus(status);
        batch.setTotalRows(parsed.rows().size() + parsed.errors().size());
        batch.setImportedRows(imported);
        batch.setFailedRows(parsed.errors().size());
        batch.setErrorDetails(parsed.errors().stream()
            .map(error -> Map.<String, Object>of("message", error))
            .toList());
        batch.setCompletedAt(OffsetDateTime.now());
        importRepository.save(batch);

        outboxWriter.write(
            "PAYMENT_IMPORT",
            batch.getId(),
            "PAYMENT_IMPORTED",
            user.organizationId(),
            user.userId(),
            Map.of(
                "sourceSystem", sourceSystem.name(),
                "totalRows", batch.getTotalRows(),
                "importedRows", imported,
                "duplicateRows", duplicates,
                "failedRows", parsed.errors().size()
            )
        );

        return new PaymentImportResponse(
            batch.getId(),
            sourceSystem,
            status,
            batch.getTotalRows(),
            imported,
            duplicates,
            parsed.errors().size(),
            parsed.errors(),
            batch.getCompletedAt()
        );
    }

    private boolean isDuplicate(
        PaymentSpreadsheetParser.PaymentRow row,
        PaymentSourceSystem sourceSystem,
        UUID organizationId
    ) {
        return row.externalPaymentId() != null
            && paymentRepository
                .existsByOrganizationIdAndSourceSystemAndExternalPaymentId(
                    organizationId,
                    sourceSystem,
                    row.externalPaymentId()
                );
    }

    private PaymentImportStatus resolveStatus(int imported, int failed) {
        if (imported == 0 && failed > 0) {
            return PaymentImportStatus.FAILED;
        }
        if (failed > 0) {
            return PaymentImportStatus.PARTIALLY_COMPLETED;
        }
        return PaymentImportStatus.COMPLETED;
    }

    private Payment toPayment(
        PaymentSpreadsheetParser.PaymentRow row,
        PaymentSourceSystem sourceSystem,
        UUID importId,
        UUID organizationId
    ) {
        Payment payment = new Payment();
        payment.setOrganizationId(organizationId);
        payment.setImportId(importId);
        payment.setSourceSystem(sourceSystem);
        payment.setExternalPaymentId(row.externalPaymentId());
        payment.setPaymentNumber(row.paymentNumber());
        payment.setPaymentDate(row.paymentDate());
        payment.setPayerInn(row.payerInn());
        payment.setPayerName(row.payerName());
        payment.setRecipientInn(row.recipientInn());
        payment.setRecipientName(row.recipientName());
        payment.setAmount(row.amount());
        payment.setCurrency(row.currency());
        payment.setPurpose(row.purpose());
        payment.setStatus(PaymentStatus.IMPORTED);
        payment.setRawData(row.rawData());
        payment.setCreatedAt(OffsetDateTime.now());
        return payment;
    }
}
