package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.sber.cargotech.claim.dto.ContractExtractionCandidateRequest;
import ru.sber.cargotech.claim.enums.ContractExtractionField;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Local parser regression runner.
 *
 * Put DOCX/TXT contracts into the project root (or pass -DcontractParserDir=...)
 * and run only this test. Known synthetic fixtures are compared with gold values;
 * unknown files are printed as UNVERIFIED instead of being silently treated as PASS.
 */
class ContractParserRegressionTest {

    private static final String ANY = "*";

    private final ContractTextExtractionService service = new ContractTextExtractionService();

    @Test
    void runContractsFromDirectory() throws Exception {
        Path directory = Path.of(System.getProperty("contractParserDir", System.getProperty("user.dir")))
            .toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            fail("Каталог для parser regression не найден: " + directory);
        }

        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    return name.endsWith(".docx") || name.endsWith(".txt");
                })
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }

        if (files.isEmpty()) {
            fail("В каталоге нет .docx/.txt договоров: " + directory);
        }

        System.out.println();
        System.out.println("================ CONTRACT PARSER REGRESSION ================");
        System.out.println("Каталог: " + directory);
        System.out.println("Файлов: " + files.size());
        System.out.println("PASS = совпало с gold; REVIEW = известная сложная конструкция/нет gold; FAIL = регрессия.");
        System.out.println();

        int passed = 0;
        int review = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        for (Path path : files) {
            String fileName = path.getFileName().toString();
            Gold gold = GOLD.get(fileName);
            String text = readContract(path);
            var extraction = service.extract(text, fileName.toLowerCase().endsWith(".docx") ? "APACHE_POI" : "TEXT");
            Map<ContractExtractionField, ContractExtractionCandidateRequest> actual = scalars(extraction.candidates());

            List<String> mismatches = gold == null ? List.of() : compare(gold, actual);
            String status;
            if (!mismatches.isEmpty()) {
                status = "FAIL";
                failed++;
                failures.add(fileName + ": " + String.join("; ", mismatches));
            } else if (gold == null || gold.manualReviewExpected()) {
                status = "REVIEW";
                review++;
            } else {
                status = "PASS";
                passed++;
            }

            System.out.printf("[%s] %s%n", status, fileName);
            printField(actual, ContractExtractionField.CONTRACT_NUMBER, "номер");
            printField(actual, ContractExtractionField.SIGNED_AT, "дата");
            printField(actual, ContractExtractionField.PAYMENT_DAYS, "срок оплаты");
            printField(actual, ContractExtractionField.PAYMENT_DAY_TYPE, "тип дней оплаты");
            printField(actual, ContractExtractionField.PAYMENT_START_EVENT, "якорь оплаты");
            printField(actual, ContractExtractionField.PAYMENT_SCHEDULE_TYPE, "платёжный календарь");
            printField(actual, ContractExtractionField.PAYMENT_WEEK_DAYS, "платёжные дни");
            printField(actual, ContractExtractionField.PENALTY_TYPE, "неустойка");
            printField(actual, ContractExtractionField.PENALTY_RATE, "ставка");
            printField(actual, ContractExtractionField.PENALTY_CAP_PERCENT, "лимит, %");
            printField(actual, ContractExtractionField.PENALTY_CAP_BASE, "база лимита");
            printField(actual, ContractExtractionField.CLAIM_RESPONSE_DAYS, "срок ответа");
            printField(actual, ContractExtractionField.CLAIM_RESPONSE_DAY_TYPE, "тип дней ответа");
            printField(actual, ContractExtractionField.JURISDICTION, "подсудность");
            long clauses = extraction.candidates().stream()
                .filter(value -> value.field() == ContractExtractionField.EXACT_CLAUSE)
                .count();
            System.out.println("  пункты-первоисточники: " + clauses);
            if (gold == null) {
                System.out.println("  ! Нет gold-эталона для этого имени файла: результат выведен для ручной проверки.");
            } else if (gold.manualReviewExpected()) {
                System.out.println("  ! В gold отмечена сложная конструкция: допустим REVIEW, но не ложное значение.");
            }
            mismatches.forEach(item -> System.out.println("  x " + item));
            System.out.println();
        }

        System.out.printf("SUMMARY: PASS=%d REVIEW=%d FAIL=%d TOTAL=%d%n", passed, review, failed, files.size());
        System.out.println("============================================================");
        System.out.println();

        if (!failures.isEmpty()) {
            fail("Contract parser regression: " + failures.size() + " файл(а/ов) с расхождениями:\n" + String.join("\n", failures));
        }
    }

    private Map<ContractExtractionField, ContractExtractionCandidateRequest> scalars(
        List<ContractExtractionCandidateRequest> candidates
    ) {
        Map<ContractExtractionField, ContractExtractionCandidateRequest> result = new TreeMap<>();
        for (var candidate : candidates) {
            if (candidate.field() != ContractExtractionField.EXACT_CLAUSE) result.put(candidate.field(), candidate);
        }
        return result;
    }

    private void printField(
        Map<ContractExtractionField, ContractExtractionCandidateRequest> actual,
        ContractExtractionField field,
        String label
    ) {
        var candidate = actual.get(field);
        String value = candidate == null || candidate.value() == null ? "—" : candidate.value();
        String source = candidate == null || candidate.clauseNumber() == null ? "" : " [п. " + candidate.clauseNumber() + "]";
        String fallback = isLegalFallback(field, candidate) ? " [legal fallback]" : "";
        System.out.printf("  %-22s %s%s%s%n", label + ":", value, source, fallback);
    }

    private boolean isLegalFallback(
        ContractExtractionField field,
        ContractExtractionCandidateRequest candidate
    ) {
        if (candidate == null || candidate.value() == null || candidate.confidence() != null) return false;
        if (field == ContractExtractionField.PENALTY_TYPE) return "ARTICLE_395".equals(candidate.value());
        if (field == ContractExtractionField.CLAIM_RESPONSE_DAYS) return "30".equals(candidate.value());
        return field == ContractExtractionField.CLAIM_RESPONSE_DAY_TYPE
            && "CALENDAR_DAYS".equals(candidate.value());
    }

    private List<String> compare(
        Gold gold,
        Map<ContractExtractionField, ContractExtractionCandidateRequest> actual
    ) {
        List<String> result = new ArrayList<>();
        check(result, actual, ContractExtractionField.CONTRACT_NUMBER, gold.contractNumber());
        check(result, actual, ContractExtractionField.SIGNED_AT, gold.signedAt());
        check(result, actual, ContractExtractionField.PAYMENT_DAYS, gold.paymentDays());
        check(result, actual, ContractExtractionField.PAYMENT_DAY_TYPE, gold.paymentDayType());
        check(result, actual, ContractExtractionField.PAYMENT_START_EVENT, gold.paymentStartEvent());
        check(result, actual, ContractExtractionField.PAYMENT_SCHEDULE_TYPE, gold.paymentScheduleType());
        check(result, actual, ContractExtractionField.PAYMENT_WEEK_DAYS, gold.paymentWeekDays());
        check(result, actual, ContractExtractionField.PENALTY_TYPE, gold.penaltyType());
        check(result, actual, ContractExtractionField.PENALTY_RATE, gold.penaltyRate());
        check(result, actual, ContractExtractionField.PENALTY_CAP_PERCENT, gold.penaltyCapPercent());
        check(result, actual, ContractExtractionField.PENALTY_CAP_BASE, gold.penaltyCapBase());
        check(result, actual, ContractExtractionField.CLAIM_RESPONSE_DAYS, gold.claimResponseDays());
        check(result, actual, ContractExtractionField.CLAIM_RESPONSE_DAY_TYPE, gold.claimResponseDayType());
        check(result, actual, ContractExtractionField.JURISDICTION, gold.jurisdiction());
        return result;
    }

    private void check(
        List<String> mismatches,
        Map<ContractExtractionField, ContractExtractionCandidateRequest> actual,
        ContractExtractionField field,
        String expected
    ) {
        if (ANY.equals(expected)) return;
        String value = actual.containsKey(field) ? actual.get(field).value() : null;
        if (!Objects.equals(expected, value)) {
            mismatches.add(field + " expected=" + display(expected) + " actual=" + display(value));
        }
    }

    private String display(String value) {
        return value == null ? "<null>" : value;
    }

    private String readContract(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".txt")) return Files.readString(path, StandardCharsets.UTF_8);
        return readDocx(path);
    }

    private String readDocx(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            StringBuilder result = new StringBuilder();

            // Headers often contain the canonical "ДОГОВОР № ... от ..." line,
            // while the body keeps number/date in separate paragraphs. Include
            // every Word header so the local runner does not lose the contract date.
            List<? extends ZipEntry> headers = zip.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().matches("word/header\\d+\\.xml"))
                .sorted(Comparator.comparing(ZipEntry::getName))
                .toList();
            for (ZipEntry header : headers) {
                appendWordXml(zip, header, result);
            }

            ZipEntry body = zip.getEntry("word/document.xml");
            if (body == null) throw new IllegalArgumentException("DOCX без word/document.xml: " + path);
            appendWordXml(zip, body, result);
            return result.toString();
        }
    }

    private void appendWordXml(ZipFile zip, ZipEntry entry, StringBuilder result) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(input);
            NodeList paragraphs = document.getElementsByTagNameNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p"
            );
            for (int i = 0; i < paragraphs.getLength(); i++) {
                StringBuilder paragraph = new StringBuilder();
                appendText(paragraphs.item(i), paragraph);
                String value = paragraph.toString().replaceAll("\\s+", " ").trim();
                if (!value.isEmpty()) result.append(value).append('\n');
            }
        }
    }

    private void appendText(Node node, StringBuilder target) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element element = (Element) node;
            if ("t".equals(element.getLocalName())) target.append(element.getTextContent());
            if ("tab".equals(element.getLocalName())) target.append(' ');
            if ("br".equals(element.getLocalName())) target.append(' ');
        }
        Node child = node.getFirstChild();
        while (child != null) {
            if (!(node.getNodeType() == Node.ELEMENT_NODE && "t".equals(node.getLocalName()))) {
                appendText(child, target);
            }
            child = child.getNextSibling();
        }
    }

    private record Gold(
        String contractNumber,
        String signedAt,
        String paymentDays,
        String paymentDayType,
        String paymentStartEvent,
        String paymentScheduleType,
        String paymentWeekDays,
        String penaltyType,
        String penaltyRate,
        String penaltyCapPercent,
        String penaltyCapBase,
        String claimResponseDays,
        String claimResponseDayType,
        String jurisdiction,
        boolean manualReviewExpected
    ) {
    }

    private static final Map<String, Gold> GOLD = new LinkedHashMap<>();

    static {
        GOLD.put("01_Северный_Контур_договор_ТЭУ.docx", new Gold(
            "ТЭ-041/26", "2026-02-12", "45", "CALENDAR_DAYS", "ACT_SIGNED", null, null,
            "CONTRACT_PENALTY", "0.05", "10", "INVOICE_AMOUNT", "20", "CALENDAR_DAYS",
            "Арбитражный суд города Москвы", false));
        GOLD.put("02_ВолгаФуд_перевозка_автотранспортом.docx", new Gold(
            "19-П/2026", "2026-04-23", "15", "BANKING_DAYS", "ACT_SIGNED", null, null,
            "CONTRACT_PENALTY", "0.1", "10", "OUTSTANDING_DEBT", "15", "CALENDAR_DAYS",
            "Арбитражный суд Республики Татарстан", false));
        GOLD.put("03_УралПромСнаб_экспедиция.docx", new Gold(
            "УПС-ЭК/77", "2026-03-05", "30", "CALENDAR_DAYS", "UNLOADING_DATE", null, null,
            "ARTICLE_395", null, null, null, "30", "CALENDAR_DAYS",
            "По месту нахождения ответчика", false));
        GOLD.put("04_НеваМаркет_рамочный_договор.docx", new Gold(
            "РТЭ-2026/118", "2026-01-18", "60", "CALENDAR_DAYS", "DOCUMENT_PACKAGE_RECEIVED", "NEXT_PAYMENT_DAY", "FRIDAY",
            "CONTRACT_PENALTY", "0.03", null, null, "30", "CALENDAR_DAYS",
            "Арбитражный суд города Санкт-Петербурга и Ленинградской области", false));
        GOLD.put("05_СибирьТрейд_ТЭО.docx", new Gold(
            "СТ-Э/260511", "2026-05-11", "10", "WORKING_DAYS", ANY, null, null,
            "CONTRACT_PENALTY", "0.07", null, null, "10", "WORKING_DAYS",
            "Арбитражный суд Новосибирской области", true));
        GOLD.put("06_ЮгАгро_организация_перевозок.docx", new Gold(
            "YA-06/2026-ТР", "2026-06-02", "21", "CALENDAR_DAYS", "TTN_SIGNED", null, null,
            null, null, null, null, "25", "CALENDAR_DAYS",
            "Арбитражный суд Краснодарского края", true));
        GOLD.put("07_БалтикИмпорт_экспедиционный.docx", new Gold(
            "БИ-ТЭ-75/26", "2026-02-27", "75", "CALENDAR_DAYS", "ACT_SIGNED", null, null,
            "ARTICLE_395", null, null, null, "14", "WORKING_DAYS",
            "Арбитражный суд Калининградской области", false));
        GOLD.put("08_ТехноСклад_логистические_услуги.docx", new Gold(
            "ТСЛ-08-26", "2026-07-08", "30", "CALENDAR_DAYS", null, null, null,
            "CONTRACT_PENALTY", "0.1", "100", "PRINCIPAL_DEBT", "20", "CALENDAR_DAYS",
            "Арбитражный суд Самарской области", true));
        GOLD.put("09_КаспийРитейл_перевозка.docx", new Gold(
            "КР/2026-44", "2026-04-14", "5", "BANKING_DAYS", "REGISTRY_INCLUDED", null, null,
            "CONTRACT_PENALTY", "0.2", "20", "SHIPMENT_COST", "10", "CALENDAR_DAYS",
            "Арбитражный суд Астраханской области", false));
        GOLD.put("10_АлтайНапитки_генеральный_договор.docx", new Gold(
            "АН-ТЭ/2026-013", "2026-03-31", "45", "CALENDAR_DAYS", "DOCUMENT_PACKAGE_RECEIVED", "NEXT_PAYMENT_DAY", "TUESDAY,THURSDAY",
            "ARTICLE_395", null, null, null, "30", "CALENDAR_DAYS",
            "Арбитражный суд Алтайского края", false));
    }
}
