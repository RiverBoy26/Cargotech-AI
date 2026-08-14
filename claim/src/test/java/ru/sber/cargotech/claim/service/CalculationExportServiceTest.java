package ru.sber.cargotech.claim.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.sber.cargotech.claim.dto.ClaimCalculationResponse;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculationExportServiceTest {
    private static final String REASON = "Неоплата оказанных услуг перевозки";
    private static final String BANK_DETAILS = "р/с 40702810900000000001\nБИК 044525225";

    @Mock private ClaimCalculationService calculationService;
    @Mock private ClaimRepository claimRepository;

    private CalculationExportService service;
    private CurrentClaimUser user;
    private UUID claimId;

    @BeforeEach
    void setUp() {
        service = new CalculationExportService(calculationService, claimRepository);
        claimId = UUID.randomUUID();
        user = new CurrentClaimUser(UUID.randomUUID(), UUID.randomUUID());

        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setOrganizationId(user.organizationId());
        claim.setClaimNumber("CLM-2026-1");
        claim.setReason(REASON);
        claim.setBankDetails(BANK_DETAILS);

        ClaimCalculationResponse calculation = new ClaimCalculationResponse(
            UUID.randomUUID(),
            claimId,
            2,
            new BigDecimal("100000.00"),
            new BigDecimal("20000.00"),
            new BigDecimal("80000.00"),
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 15),
            14,
            PenaltyType.ARTICLE_395,
            new BigDecimal("18.00"),
            new BigDecimal("552.33"),
            new BigDecimal("80552.33"),
            "80000 × 18% × 14 / 365",
            user.userId(),
            OffsetDateTime.now()
        );

        when(claimRepository.findByIdAndOrganizationId(claimId, user.organizationId()))
            .thenReturn(Optional.of(claim));
        when(calculationService.recalculate(user, claimId)).thenReturn(calculation);
    }

    @Test
    void includesReasonAndBankDetailsInXlsx() throws Exception {
        var exported = service.export(user, claimId, "xlsx");

        assertThat(exported.filename()).endsWith(".xlsx");
        assertThat(exported.contentType())
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.content()))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder content = new StringBuilder();
            workbook.getSheetAt(0).forEach(row -> row.forEach(cell ->
                content.append(formatter.formatCellValue(cell)).append('\n')
            ));
            assertThat(content)
                .contains("Основание претензии")
                .contains(REASON)
                .contains("Банковские реквизиты")
                .contains("р/с 40702810900000000001")
                .contains("БИК 044525225");
        }
    }

    @Test
    void includesReasonAndBankDetailsInPdf() throws Exception {
        var exported = service.export(user, claimId, "pdf");

        assertThat(exported.filename()).endsWith(".pdf");
        assertThat(exported.contentType()).isEqualTo("application/pdf");
        try (PDDocument document = Loader.loadPDF(exported.content())) {
            String content = new PDFTextStripper().getText(document);
            assertThat(content)
                .contains("Основание претензии: " + REASON)
                .contains("Банковские реквизиты:")
                .contains("р/с 40702810900000000001")
                .contains("БИК 044525225");
        }
    }
}
