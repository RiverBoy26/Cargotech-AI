package ru.sber.cargotech.document.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.document.config.DocumentPdfProperties;
import ru.sber.cargotech.document.exception.DocumentException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ClaimPdfRenderer {

    private static final float FONT_SIZE = 12f;
    private static final float LINE_HEIGHT = 16f;
    private static final float MARGIN = 56f;

    private final TemplateTextRenderer textRenderer;
    private final DocumentPdfProperties properties;

    public ClaimPdfRenderer(
        TemplateTextRenderer textRenderer,
        DocumentPdfProperties properties
    ) {
        this.textRenderer = textRenderer;
        this.properties = properties;
    }

    public byte[] render(String templateContent, Map<String, Object> data) {
        String resolved = textRenderer
            .render(templateContent, data, false)
            .content();

        try (PDDocument document = new PDDocument();
             InputStream fontInput = Files.newInputStream(resolveFontPath());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            PDType0Font font = PDType0Font.load(document, fontInput);
            float usableWidth = PDRectangle.A4.getWidth() - (MARGIN * 2);
            List<String> lines = wrap(resolved, font, usableWidth);
            int linesPerPage = Math.max(
                1,
                (int) ((PDRectangle.A4.getHeight() - (MARGIN * 2)) / LINE_HEIGHT)
            );

            for (int start = 0; start < lines.size(); start += linesPerPage) {
                int end = Math.min(start + linesPerPage, lines.size());
                writePage(document, font, lines.subList(start, end));
            }

            if (lines.isEmpty()) {
                writePage(document, font, List.of(""));
            }

            document.save(output);
            return output.toByteArray();
        } catch (DocumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw DocumentException.unprocessable(
                "Не удалось сформировать PDF-документ претензии"
            );
        }
    }

    private void writePage(
        PDDocument document,
        PDType0Font font,
        List<String> lines
    ) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(font, FONT_SIZE);
            content.setLeading(LINE_HEIGHT);
            content.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);

            for (String line : lines) {
                content.showText(line == null ? "" : line);
                content.newLine();
            }

            content.endText();
        }
    }

    private List<String> wrap(
        String text,
        PDType0Font font,
        float maxWidth
    ) throws Exception {
        List<String> result = new ArrayList<>();

        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                result.add("");
                continue;
            }

            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.isEmpty()
                    ? word
                    : line + " " + word;

                float width = font.getStringWidth(candidate) / 1000f * FONT_SIZE;
                if (width <= maxWidth || line.isEmpty()) {
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
        List<Path> candidates = new ArrayList<>();
        if (properties.fontPath() != null && !properties.fontPath().isBlank()) {
            candidates.add(Path.of(properties.fontPath()));
        }
        candidates.add(Path.of("C:/Windows/Fonts/arial.ttf"));
        candidates.add(Path.of("C:/Windows/Fonts/times.ttf"));
        candidates.add(Path.of("/usr/share/fonts/truetype/liberation2/LiberationSerif-Regular.ttf"));
        candidates.add(Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"));

        return candidates.stream()
            .map(Path::toAbsolutePath)
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> DocumentException.unprocessable(
                "Не найден Unicode TTF-шрифт. Укажите DOCUMENT_PDF_FONT_PATH"
            ));
    }
}
