package ru.sber.cargotech.claim.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ContractRagChunker {

    static final int MAX_CHUNK_LENGTH = 1_800;

    private static final Pattern PAGE_MARKER = Pattern.compile("^\\[\\[PAGE:(\\d+)]]$");
    private static final Pattern SECTION = Pattern.compile(
        "(?iu)^(статья|раздел|глава)\\s+([0-9IVXLCDM]+(?:\\.[0-9]+)*)\\.?\\s*[-–—:]?\\s*(.*)$"
    );
    private static final Pattern BARE_NUMBERED_SECTION = Pattern.compile(
        "(?iu)^([0-9IVXLCDM]+)\\.\\s+(.+)$"
    );
    private static final Pattern CLAUSE = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\.?\\s*(.*)$");
    private static final Pattern PASSPORT = Pattern.compile("\\b\\d{4}\\s?\\d{6}\\b");
    private static final Pattern BANK_ACCOUNT = Pattern.compile("\\b\\d{20}\\b");
    private static final Pattern CARD = Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("(?:\\+7|8)[\\s(-]?\\d{3}[\\s)-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\b");
    private static final Pattern VEHICLE_NUMBER = Pattern.compile(
        "(?iu)(?<![А-ЯA-Z0-9])[АВЕКМНОРСТУХABEKMHOPCTYX]\\s?\\d{3}\\s?[АВЕКМНОРСТУХABEKMHOPCTYX]{2}\\s?\\d{2,3}(?![А-ЯA-Z0-9])"
    );
    private static final Pattern VEHICLE_ABBREVIATION = Pattern.compile("(?iu)(?<!\\p{L})тс(?!\\p{L})");
    private static final Pattern REQUISITES_HEADING = Pattern.compile(
        "(?iu)^(?:(?:статья|раздел|глава)\\s+[0-9IVXLCDM]+(?:\\.[0-9]+)*\\.?\\s*[-–—:]?\\s*|[0-9IVXLCDM]+\\.?\\s+)?"
            + "(?:реквизиты(?:\\s+(?:и|,)\\s*(?:адреса|подписи)(?:\\s+сторон)?)?"
            + "|адреса\\s+и\\s+подписи(?:\\s+сторон)?"
            + "|(?:юридические\\s+)?адреса,?\\s+банковские\\s+реквизиты\\s+и\\s+подписи(?:\\s+сторон)?)"
            + "\\s*[.:]?$"
    );

    public List<ContractSourceChunk> chunk(String originalText) {
        if (originalText == null || originalText.isBlank()) {
            return List.of();
        }

        List<SourceBlock> blocks = new ArrayList<>();
        String sectionTitle = "Общие положения";
        String sectionPath = sectionTitle;
        boolean sectionNeedsTitle = false;
        boolean skipRequisites = false;
        int page = 1;
        SourceBlock current = null;

        for (String rawLine : originalText.replace('\u0000', ' ').split("\\R")) {
            String line = normalizeLine(rawLine);
            if (line.isBlank()) continue;

            Matcher pageMarker = PAGE_MARKER.matcher(line);
            if (pageMarker.matches()) {
                page = Integer.parseInt(pageMarker.group(1));
                continue;
            }

            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                addBlock(blocks, current);
                current = null;
                String nextTitle = (section.group(1) + " " + section.group(2)).trim();
                String inlineTitle = normalizeLine(section.group(3));
                if (!inlineTitle.isBlank()) nextTitle += ". " + inlineTitle;
                skipRequisites = isRequisitesHeading(line);
                sectionTitle = nextTitle;
                sectionPath = nextTitle;
                sectionNeedsTitle = inlineTitle.isBlank();
                continue;
            }

            if (isRequisitesHeading(line)) {
                addBlock(blocks, current);
                current = null;
                skipRequisites = true;
                sectionNeedsTitle = false;
                continue;
            }
            if (skipRequisites) continue;

            Matcher bareSection = BARE_NUMBERED_SECTION.matcher(line);
            if (bareSection.matches()) {
                addBlock(blocks, current);
                current = null;
                sectionTitle = bareSection.group(1) + ". " + normalizeLine(bareSection.group(2));
                sectionPath = sectionTitle;
                sectionNeedsTitle = false;
                continue;
            }

            Matcher clause = CLAUSE.matcher(line);
            if (clause.matches()) {
                addBlock(blocks, current);
                current = new SourceBlock(sectionTitle, sectionPath, clause.group(1), page);
                current.append(line);
                sectionNeedsTitle = false;
                continue;
            }

            if (sectionNeedsTitle && isShortTitle(line)) {
                sectionTitle += ". " + line;
                sectionPath = sectionTitle;
                sectionNeedsTitle = false;
                continue;
            }

            if (current == null) current = new SourceBlock(sectionTitle, sectionPath, null, page);
            current.append(line);
        }
        addBlock(blocks, current);

        List<ContractSourceChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (SourceBlock block : blocks) {
            String safeText = maskSensitiveData(block.text());
            if (safeText.isBlank()) continue;
            Classification classification = classify(safeText);
            List<String> parts = splitLongText(safeText);
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                chunks.add(new ContractSourceChunk(
                    chunkIndex,
                    partIndex,
                    block.sectionTitle(),
                    block.sectionPath(),
                    block.clauseNumber(),
                    classification.topic(),
                    classification.chunkType(),
                    classification.claimType(),
                    parts.get(partIndex),
                    block.sourcePage()
                ));
            }
            chunkIndex++;
        }
        return List.copyOf(chunks);
    }

    private Classification classify(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean sanction = containsAny(lower, "неустойк", "штраф", "пеня", "пеню", "пени", "пеней");
        boolean vehicleReference = containsAny(lower, "транспортн", "автомобил")
            || VEHICLE_ABBREVIATION.matcher(lower).find();
        boolean loadingReference = containsAny(lower, "погруз", "загруз");
        boolean loadingFailure = containsAny(lower, "срыв погруз", "отказ от погруз")
            || (containsAny(lower, "неподач", "непредостав") && (vehicleReference || loadingReference));
        if (sanction && loadingFailure) {
            return new Classification("LOADING_FAILURE_PENALTY", "LOADING_FAILURE", "Штраф за непредоставление транспорта");
        }

        boolean pretrial = containsAny(lower, "претензи", "досудеб")
            && containsAny(
                lower,
                "порядок", "срок", "ответ", "рассматрива", "направ",
                "урегулиров", "до обращения в суд", "письменн", "требован"
            );
        if (pretrial) {
            return new Classification("PRETRIAL_ORDER", "ALL", "Претензионный порядок");
        }

        if (sanction && containsAny(lower, "просроч", "оплат", "задолж", "денежн")) {
            return new Classification("CONTRACT_PENALTY", "PAYMENT_DELAY", "Ответственность за просрочку оплаты");
        }
        if (containsAny(lower, "пода", "предостав") && vehicleReference && loadingReference) {
            return new Classification("VEHICLE_SUPPLY_DUTY", "LOADING_FAILURE", "Обязанность подать транспорт");
        }

        boolean paymentCore = containsAny(
            lower,
            "оплат", "платеж", "платёж",
            "расчеты производ", "расчёты производ",
            "расчеты осуществ", "расчёты осуществ"
        );
        boolean paymentTiming = containsAny(
            lower,
            "срок", "дн", "в течение", "после", "с даты", "с момента",
            "не позднее", "банковск", "календарн", "рабоч"
        );
        boolean paymentDocuments = containsAny(
            lower,
            "закрывающ", "комплект", "реестр", "акт", "упд",
            "накладн", "счет на оплату", "счёт на оплату"
        );
        boolean unrelatedPayment = containsAny(
            lower,
            "оплата простоя", "оплате простоя", "оплату простоя",
            "оплаты простоя"
        );
        if (paymentCore && !unrelatedPayment && (paymentTiming || paymentDocuments)) {
            return new Classification("PAYMENT_TERM", "PAYMENT_DELAY", "Условия оплаты");
        }
        return new Classification("CONTRACT_GENERAL", "ALL", "Условия договора");
    }

    private List<String> splitLongText(String text) {
        if (text.length() <= MAX_CHUNK_LENGTH) return List.of(text);
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_LENGTH, text.length());
            if (end < text.length()) {
                int minCut = start + (MAX_CHUNK_LENGTH * 3 / 5);
                int paragraph = text.lastIndexOf('\n', end);
                int sentence = text.lastIndexOf(". ", end);
                int whitespace = text.lastIndexOf(' ', end);
                if (paragraph >= minCut) end = paragraph + 1;
                else if (sentence >= minCut) end = sentence + 1;
                else if (whitespace >= minCut) end = whitespace + 1;
            }
            String part = text.substring(start, end).trim();
            if (!part.isBlank()) result.add(part);
            start = end;
        }
        return result;
    }

    private String maskSensitiveData(String text) {
        String safe = PASSPORT.matcher(text).replaceAll("[ПАСПОРТНЫЕ ДАННЫЕ УДАЛЕНЫ]");
        safe = PHONE.matcher(safe).replaceAll("[ТЕЛЕФОН УДАЛЕН]");
        safe = EMAIL.matcher(safe).replaceAll("[EMAIL УДАЛЕН]");
        safe = CARD.matcher(safe).replaceAll("[НОМЕР КАРТЫ УДАЛЕН]");
        safe = BANK_ACCOUNT.matcher(safe).replaceAll("[БАНКОВСКИЙ СЧЕТ УДАЛЕН]");
        return VEHICLE_NUMBER.matcher(safe).replaceAll("[ГОСНОМЕР УДАЛЕН]");
    }

    private boolean isRequisitesHeading(String line) {
        if (line.length() > 180) return false;
        return REQUISITES_HEADING.matcher(line).matches();
    }

    private boolean isShortTitle(String line) {
        return line.length() <= 140 && !line.matches(".*[.;:]$") && !CLAUSE.matcher(line).matches();
    }

    private String normalizeLine(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll(" {2,}", " ")
            .trim();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private void addBlock(List<SourceBlock> blocks, SourceBlock block) {
        if (block != null && !block.text().isBlank()) blocks.add(block);
    }

    private static final class SourceBlock {
        private final String sectionTitle;
        private final String sectionPath;
        private final String clauseNumber;
        private final int sourcePage;
        private final StringBuilder text = new StringBuilder();

        private SourceBlock(String sectionTitle, String sectionPath, String clauseNumber, int sourcePage) {
            this.sectionTitle = sectionTitle;
            this.sectionPath = sectionPath;
            this.clauseNumber = clauseNumber;
            this.sourcePage = sourcePage;
        }

        private void append(String value) {
            if (!text.isEmpty()) text.append('\n');
            text.append(value);
        }

        private String sectionTitle() { return sectionTitle; }
        private String sectionPath() { return sectionPath; }
        private String clauseNumber() { return clauseNumber; }
        private int sourcePage() { return sourcePage; }
        private String text() { return text.toString().trim(); }
    }

    private record Classification(String chunkType, String claimType, String topic) {}

    public record ContractSourceChunk(
        int chunkIndex,
        int partIndex,
        String sectionTitle,
        String sectionPath,
        String clauseNumber,
        String clauseTopic,
        String chunkType,
        String claimType,
        String text,
        int sourcePage
    ) {}
}
