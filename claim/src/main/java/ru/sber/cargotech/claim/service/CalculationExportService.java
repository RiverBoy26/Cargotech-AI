package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.claim.dto.ClaimCalculationResponse;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.ClaimRepository;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CalculationExportService {
    private static final String XLSX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ClaimCalculationService calculationService;
    private final ClaimRepository claimRepository;

    @Transactional
    public ExportedCalculation export(
        CurrentClaimUser user,
        UUID claimId,
        String requestedFormat
    ) {
        String format = requestedFormat == null
            ? ""
            : requestedFormat.trim().toLowerCase(Locale.ROOT);
        if (!format.equals("pdf") && !format.equals("xlsx")) {
            throw ClaimException.validation("Поддерживаются форматы PDF и XLSX");
        }

        var claim = claimRepository.findByIdAndOrganizationId(claimId, user.organizationId())
            .orElseThrow(() -> ClaimException.notFound("Претензия не найдена"));
        ClaimCalculationResponse calculation = calculationService.recalculate(user, claimId);
        String baseName = "Расчет_" + safeFilename(claim.getClaimNumber());

        try {
            return format.equals("xlsx")
                ? new ExportedCalculation(
                    baseName + ".xlsx",
                    XLSX_CONTENT_TYPE,
                    renderXlsx(claim, calculation)
                )
                : new ExportedCalculation(
                    baseName + ".pdf",
                    MediaTypeNames.PDF,
                    renderPdf(claim, calculation)
                );
        } catch (ClaimException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ClaimException.conflict("Не удалось сформировать файл расчёта");
        }
    }

    private byte[] renderXlsx(ClaimEntity claim, ClaimCalculationResponse calculation) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Расчёт задолженности");
            sheet.setColumnWidth(0, 34 * 256);
            sheet.setColumnWidth(1, 28 * 256);

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(titleFont);

            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("Расчёт задолженности по претензии " + claim.getClaimNumber());
            title.getCell(0).setCellStyle(titleStyle);

            int row = 2;
            row = writeRow(sheet, row, "Основание претензии", claim.getReason());
            row = writeRow(sheet, row, "Банковские реквизиты", claim.getBankDetails());
            row = writeRow(sheet, row, "Версия расчёта", calculation.calculationVersion());
            row = writeRow(sheet, row, "Сумма перевозки", calculation.principalDebt());
            row = writeRow(sheet, row, "Учтено платежей", calculation.paidAmount());
            row = writeRow(sheet, row, "Остаток основного долга", calculation.remainingDebt());
            row = writeRow(sheet, row, "Дата начала просрочки", calculation.overdueStartDate());
            row = writeRow(sheet, row, "Дата расчёта", calculation.calculationDate());
            row = writeRow(sheet, row, "Дней просрочки", calculation.overdueDays());
            row = writeRow(sheet, row, "Вид неустойки", calculation.penaltyType());
            row = writeRow(sheet, row, "Ставка, %", calculation.penaltyRate());
            row = writeRow(sheet, row, "Неустойка", calculation.penaltyAmount());
            row = writeRow(sheet, row, "Итого к оплате", calculation.totalAmount());
            writeRow(sheet, row, "Формула", calculation.formula());

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private int writeRow(Sheet sheet, int index, String label, Object value) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value == null ? "—" : value.toString());
        return index + 1;
    }

    private byte[] renderPdf(ClaimEntity claim, ClaimCalculationResponse calculation) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("РАСЧЁТ ЗАДОЛЖЕННОСТИ");
        lines.add("Претензия: " + text(claim.getClaimNumber()));
        lines.add("Основание претензии: " + text(claim.getReason()));
        lines.add("Банковские реквизиты:");
        addTextLines(lines, claim.getBankDetails());
        lines.add("");
        lines.add("Сумма перевозки: " + money(calculation.principalDebt()));
        lines.add("Учтено платежей: " + money(calculation.paidAmount()));
        lines.add("Остаток основного долга: " + money(calculation.remainingDebt()));
        lines.add("Дата начала просрочки: " + text(calculation.overdueStartDate()));
        lines.add("Дата расчёта: " + text(calculation.calculationDate()));
        lines.add("Дней просрочки: " + text(calculation.overdueDays()));
        lines.add("Вид неустойки: " + text(calculation.penaltyType()));
        lines.add("Ставка: " + text(calculation.penaltyRate()) + "%");
        lines.add("Неустойка: " + money(calculation.penaltyAmount()));
        lines.add("Итого к оплате: " + money(calculation.totalAmount()));
        lines.add("");
        lines.add("Формула: " + text(calculation.formula()));

        try (PDDocument document = new PDDocument();
             InputStream fontInput = Files.newInputStream(resolveFontPath());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, fontInput);
            List<String> wrappedLines = wrap(lines, font, 11f, PDRectangle.A4.getWidth() - 104f);
            int linesPerPage = (int) ((PDRectangle.A4.getHeight() - 104f) / 18f);
            for (int start = 0; start < wrappedLines.size(); start += linesPerPage) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 11f);
                    content.setLeading(18f);
                    content.newLineAtOffset(52f, page.getMediaBox().getHeight() - 52f);
                    int end = Math.min(start + linesPerPage, wrappedLines.size());
                    for (String line : wrappedLines.subList(start, end)) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private void addTextLines(List<String> target, String value) {
        String[] lines = text(value).split("\\R", -1);
        target.addAll(List.of(lines));
    }

    private List<String> wrap(List<String> lines, PDType0Font font, float size, float width) throws Exception {
        List<String> result = new ArrayList<>();
        for (String source : lines) {
            if (source.isBlank()) {
                result.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : source.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (line.isEmpty() || font.getStringWidth(candidate) / 1000f * size <= width) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            result.add(line.toString());
        }
        return result;
    }

    private Path resolveFontPath() {
        return List.of(
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
                Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
                Path.of("C:/Windows/Fonts/arial.ttf")
            ).stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> ClaimException.conflict("Не найден шрифт для PDF-расчёта"));
    }

    private String safeFilename(String value) {
        String source = value == null || value.isBlank() ? "claim" : value;
        return source.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
    }

    private String text(Object value) {
        if (value == null || value.toString().isBlank()) return "—";
        return value.toString();
    }

    private String money(Object value) {
        return text(value) + " руб.";
    }

    public record ExportedCalculation(String filename, String contentType, byte[] content) {
    }

    private static final class MediaTypeNames {
        private static final String PDF = "application/pdf";
    }
}
