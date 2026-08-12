package ru.sber.cargotech.document.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sber.cargotech.document.dto.InternalDocumentTextResponse;
import ru.sber.cargotech.document.entity.Document;
import ru.sber.cargotech.document.entity.DocumentText;
import ru.sber.cargotech.document.exception.DocumentException;
import ru.sber.cargotech.document.repository.DocumentRepository;
import ru.sber.cargotech.document.repository.DocumentTextRepository;
import ru.sber.cargotech.document.storage.LocalDocumentStorageService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentTextExtractionService {

    private static final int MAX_TEXT_LENGTH = 2_000_000;

    private final DocumentRepository documentRepository;
    private final DocumentTextRepository textRepository;
    private final LocalDocumentStorageService storageService;

    public DocumentTextExtractionService(
        DocumentRepository documentRepository,
        DocumentTextRepository textRepository,
        LocalDocumentStorageService storageService
    ) {
        this.documentRepository = documentRepository;
        this.textRepository = textRepository;
        this.storageService = storageService;
    }

    @Transactional
    public DocumentText extractAndSave(Document document) {
        ExtractedText extracted = extract(document);
        if (extracted.text().isBlank()) {
            throw DocumentException.unprocessable(
                "В файле договора не найден текст. Загрузите PDF с текстовым слоем, DOCX или TXT"
            );
        }

        DocumentText value = textRepository.findByDocument_Id(document.getId())
            .orElseGet(DocumentText::new);
        value.setDocument(document);
        value.setTextContent(extracted.text());
        value.setExtractionMethod(extracted.method());
        value.setPageCount(extracted.pageCount());
        value.setLanguage("ru");
        value.setExtractionMetadata(Map.of(
            "originalName", document.getFile().getOriginalName(),
            "contentType", document.getFile().getContentType()
        ));
        return textRepository.save(value);
    }

    @Transactional(readOnly = true)
    public InternalDocumentTextResponse getInternal(UUID documentId) {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> DocumentException.notFound("Документ не найден"));
        DocumentText text = textRepository.findByDocument_Id(documentId)
            .orElseThrow(() -> DocumentException.unprocessable("Текст документа ещё не извлечён"));
        return new InternalDocumentTextResponse(
            document.getId(), text.getTextContent(), text.getExtractionMethod(), text.getPageCount()
        );
    }

    private ExtractedText extract(Document document) {
        String name = document.getFile().getOriginalName().toLowerCase(Locale.ROOT);
        String contentType = document.getFile().getContentType() == null
            ? ""
            : document.getFile().getContentType().toLowerCase(Locale.ROOT);
        Resource resource = storageService.load(document.getFile().getStorageKey());

        try {
            if (name.endsWith(".pdf") || contentType.equals("application/pdf")) {
                return extractPdf(resource);
            }
            if (name.endsWith(".docx") || contentType.contains("wordprocessingml")) {
                return extractDocx(resource);
            }
            if (name.endsWith(".txt") || contentType.startsWith("text/")) {
                return extractText(resource);
            }
        } catch (IOException exception) {
            throw DocumentException.unprocessable("Не удалось извлечь текст из файла договора");
        }

        throw DocumentException.unprocessable("Для договора поддерживаются файлы PDF, DOCX и TXT");
    }

    private ExtractedText extractPdf(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream(); PDDocument pdf = Loader.loadPDF(input.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                if (page > 1) text.append("\n[[PAGE:").append(page).append("]]\n");
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                text.append(stripper.getText(pdf));
            }
            return normalized(text.toString(), "PDFBOX", pdf.getNumberOfPages());
        }
    }

    private ExtractedText extractDocx(Resource resource) throws IOException {
        try (
            InputStream input = resource.getInputStream();
            XWPFDocument docx = new XWPFDocument(input);
            XWPFWordExtractor extractor = new XWPFWordExtractor(docx)
        ) {
            int pageCount = Math.max(1, docx.getProperties().getExtendedProperties().getUnderlyingProperties().getPages());
            return normalized(extractor.getText(), "APACHE_POI", pageCount);
        }
    }

    private ExtractedText extractText(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return normalized(new String(input.readAllBytes(), StandardCharsets.UTF_8), "UTF8_TEXT", 1);
        }
    }

    private ExtractedText normalized(String raw, String method, int pages) {
        String text = raw == null ? "" : raw
            .replace('\u0000', ' ')
            .replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll("[ ]{2,}", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }
        return new ExtractedText(text, method, Math.max(1, pages));
    }

    private record ExtractedText(String text, String method, int pageCount) {}
}
