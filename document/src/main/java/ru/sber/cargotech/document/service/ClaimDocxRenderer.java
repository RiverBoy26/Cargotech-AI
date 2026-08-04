package ru.sber.cargotech.document.service;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.document.exception.DocumentException;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Component
public class ClaimDocxRenderer {

    private final TemplateTextRenderer textRenderer;

    public ClaimDocxRenderer(TemplateTextRenderer textRenderer) {
        this.textRenderer = textRenderer;
    }

    public byte[] render(String templateContent, Map<String, Object> data) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            String resolved = textRenderer
                .render(templateContent, data, false)
                .content();

            for (String paragraphText : resolved.split("\\R", -1)) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.setAlignment(ParagraphAlignment.BOTH);
                paragraph.setSpacingAfter(120);

                XWPFRun run = paragraph.createRun();
                run.setFontFamily("Times New Roman");
                run.setFontSize(12);
                run.setText(paragraphText == null ? "" : paragraphText);
            }

            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw DocumentException.unprocessable(
                "Не удалось сформировать DOCX-документ претензии"
            );
        }
    }
}
