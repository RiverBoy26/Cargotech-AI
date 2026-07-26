package ru.sber.cargotech.payment.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ru.sber.cargotech.payment.exception.PaymentException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class PaymentSpreadsheetParser {
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd.MM.uuuu"),
        DateTimeFormatter.ofPattern("dd/MM/uuuu")
    );

    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
        Map.entry("external_payment_id", "external_payment_id"),
        Map.entry("идентификатор_платежа", "external_payment_id"),
        Map.entry("payment_number", "payment_number"),
        Map.entry("номер_документа", "payment_number"),
        Map.entry("номер_платежа", "payment_number"),
        Map.entry("payment_date", "payment_date"),
        Map.entry("дата_операции", "payment_date"),
        Map.entry("дата_платежа", "payment_date"),
        Map.entry("дата", "payment_date"),
        Map.entry("payer_inn", "payer_inn"),
        Map.entry("инн_контрагента", "payer_inn"),
        Map.entry("инн_плательщика", "payer_inn"),
        Map.entry("payer_name", "payer_name"),
        Map.entry("контрагент", "payer_name"),
        Map.entry("плательщик", "payer_name"),
        Map.entry("recipient_inn", "recipient_inn"),
        Map.entry("инн_получателя", "recipient_inn"),
        Map.entry("recipient_name", "recipient_name"),
        Map.entry("получатель", "recipient_name"),
        Map.entry("amount", "amount"),
        Map.entry("сумма_операции", "amount"),
        Map.entry("сумма", "amount"),
        Map.entry("currency", "currency"),
        Map.entry("валюта", "currency"),
        Map.entry("purpose", "purpose"),
        Map.entry("описание_операции", "purpose"),
        Map.entry("назначение_платежа", "purpose"),
        Map.entry("назначение", "purpose")
    );

    public ParseResult parse(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null
            || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw PaymentException.unprocessable(
                "Для MVP поддерживается только XLSX-файл"
            );
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("ru-RU"));
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());

            if (headerRow == null) {
                throw PaymentException.unprocessable("XLSX не содержит заголовков");
            }

            Map<String, Integer> columns = readColumns(headerRow, formatter);
            requireColumn(columns, "payment_date");
            requireColumn(columns, "amount");

            List<PaymentRow> rows = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (int index = headerRow.getRowNum() + 1;
                 index <= sheet.getLastRowNum();
                 index++) {
                Row row = sheet.getRow(index);
                if (row == null || isEmpty(row, formatter)) {
                    continue;
                }

                try {
                    rows.add(readRow(row, columns, formatter));
                } catch (RuntimeException exception) {
                    errors.add(
                        "Строка %d: %s".formatted(index + 1, exception.getMessage())
                    );
                }
            }

            return new ParseResult(List.copyOf(rows), List.copyOf(errors));
        } catch (IOException exception) {
            throw PaymentException.unprocessable("Не удалось прочитать XLSX-файл");
        }
    }

    private Map<String, Integer> readColumns(
        Row headerRow,
        DataFormatter formatter
    ) {
        Map<String, Integer> columns = new HashMap<>();

        for (Cell cell : headerRow) {
            String normalized = normalize(formatter.formatCellValue(cell));
            String canonical = HEADER_ALIASES.getOrDefault(normalized, normalized);
            columns.put(canonical, cell.getColumnIndex());
        }

        return columns;
    }

    private PaymentRow readRow(
        Row row,
        Map<String, Integer> columns,
        DataFormatter formatter
    ) {
        Map<String, Object> rawData = new LinkedHashMap<>();
        columns.forEach((name, index) ->
            rawData.put(name, readString(row, index, formatter))
        );

        return new PaymentRow(
            optional(row, columns, formatter, "external_payment_id"),
            optional(row, columns, formatter, "payment_number"),
            readDate(row, columns.get("payment_date"), formatter),
            optional(row, columns, formatter, "payer_inn"),
            optional(row, columns, formatter, "payer_name"),
            optional(row, columns, formatter, "recipient_inn"),
            optional(row, columns, formatter, "recipient_name"),
            readAmount(row, columns.get("amount"), formatter),
            Optional.ofNullable(optional(row, columns, formatter, "currency"))
                .orElse("RUB")
                .toUpperCase(Locale.ROOT),
            optional(row, columns, formatter, "purpose"),
            rawData
        );
    }

    private LocalDate readDate(
        Row row,
        int column,
        DataFormatter formatter
    ) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            throw new IllegalArgumentException("дата платежа не заполнена");
        }

        if (cell.getCellType() == CellType.NUMERIC
            && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        }

        String value = formatter.formatCellValue(cell).trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (RuntimeException ignored) {
                // Следующий формат.
            }
        }

        throw new IllegalArgumentException("некорректная дата: " + value);
    }

    private BigDecimal readAmount(
        Row row,
        int column,
        DataFormatter formatter
    ) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            throw new IllegalArgumentException("сумма не заполнена");
        }

        BigDecimal amount;
        if (cell.getCellType() == CellType.NUMERIC) {
            amount = BigDecimal.valueOf(cell.getNumericCellValue());
        } else {
            String normalized = formatter.formatCellValue(cell)
                .replace(" ", "")
                .replace(" ", "")
                .replace(",", ".");
            try {
                amount = new BigDecimal(normalized);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("некорректная сумма: " + normalized);
            }
        }

        if (amount.signum() == 0) {
            throw new IllegalArgumentException("сумма не может быть равна нулю");
        }

        return amount;
    }

    private String optional(
        Row row,
        Map<String, Integer> columns,
        DataFormatter formatter,
        String name
    ) {
        Integer column = columns.get(name);
        if (column == null) {
            return null;
        }

        String value = readString(row, column, formatter);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String readString(
        Row row,
        int column,
        DataFormatter formatter
    ) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? null : formatter.formatCellValue(cell);
    }

    private boolean isEmpty(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private void requireColumn(Map<String, Integer> columns, String name) {
        if (!columns.containsKey(name)) {
            throw PaymentException.unprocessable(
                "Отсутствует обязательный столбец: " + name
            );
        }
    }

    private String normalize(String value) {
        return value
            .trim()
            .toLowerCase(Locale.ROOT)
            .replace('ё', 'е')
            .replaceAll("[\\s\\-./]+", "_")
            .replaceAll("[^a-zа-я0-9_]", "");
    }

    public record PaymentRow(
        String externalPaymentId,
        String paymentNumber,
        LocalDate paymentDate,
        String payerInn,
        String payerName,
        String recipientInn,
        String recipientName,
        BigDecimal amount,
        String currency,
        String purpose,
        Map<String, Object> rawData
    ) {
    }

    public record ParseResult(
        List<PaymentRow> rows,
        List<String> errors
    ) {
    }
}
