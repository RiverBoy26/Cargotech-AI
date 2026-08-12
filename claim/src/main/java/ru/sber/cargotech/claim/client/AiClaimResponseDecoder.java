package ru.sber.cargotech.claim.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimResponse;
import ru.sber.cargotech.claim.exception.ClaimException;

import java.io.ByteArrayInputStream;
import java.util.List;

@Component
public class AiClaimResponseDecoder {

    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiGenerateClaimResponse decode(
        byte[] body,
        MediaType contentType,
        String filename
    ) {
        if (body == null || body.length == 0) {
            throw ClaimException.conflict("AI-модуль вернул пустой ответ");
        }

        if (isDocx(contentType, filename, body)) {
            return responseFromDocx(body);
        }

        try {
            return objectMapper.readValue(body, AiGenerateClaimResponse.class);
        } catch (Exception exception) {
            throw ClaimException.conflict(
                "AI-модуль вернул ответ в неподдерживаемом формате"
            );
        }
    }

    private AiGenerateClaimResponse responseFromDocx(byte[] body) {
        try (
            XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(body)
            );
            XWPFWordExtractor extractor = new XWPFWordExtractor(document)
        ) {
            String claimText = normalize(extractor.getText());
            if (claimText.isBlank()) {
                throw ClaimException.conflict(
                    "DOCX от AI-модуля не содержит текста претензии"
                );
            }

            return new AiGenerateClaimResponse(
                true,
                "SUCCESS",
                List.of(),
                new AiGenerateClaimResponse.GeneratedClaim(
                    claimText,
                    "Текст претензии извлечён из DOCX, полученного от AI-модуля",
                    List.of(),
                    true
                ),
                null,
                List.of()
            );
        } catch (ClaimException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ClaimException.conflict(
                "Не удалось извлечь текст претензии из DOCX AI-модуля"
            );
        }
    }

    private boolean isDocx(
        MediaType contentType,
        String filename,
        byte[] body
    ) {
        if (contentType != null && DOCX_MEDIA_TYPE.isCompatibleWith(contentType)) {
            return true;
        }
        if (filename != null && filename.toLowerCase().endsWith(".docx")) {
            return true;
        }
        return body.length >= 4
            && body[0] == 'P'
            && body[1] == 'K'
            && body[2] == 3
            && body[3] == 4;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[\\t ]+\\n", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
