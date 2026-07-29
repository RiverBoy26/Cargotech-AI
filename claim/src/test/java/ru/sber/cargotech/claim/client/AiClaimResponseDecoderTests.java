package ru.sber.cargotech.claim.client;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimResponse;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AiClaimResponseDecoderTests {

    private final AiClaimResponseDecoder decoder =
        new AiClaimResponseDecoder();

    @Test
    void extractsClaimTextFromDocxResponse() throws Exception {
        byte[] docx;
        try (
            XWPFDocument document = new XWPFDocument();
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            document.createParagraph()
                .createRun()
                .setText("ПРЕТЕНЗИЯ");
            document.createParagraph()
                .createRun()
                .setText("Требуем погасить задолженность.");
            document.write(output);
            docx = output.toByteArray();
        }

        AiGenerateClaimResponse response = decoder.decode(
            docx,
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ),
            "generated-claim.docx"
        );

        assertThat(response.success()).isTrue();
        assertThat(response.generatedClaim().claimText())
            .isEqualTo("ПРЕТЕНЗИЯ\nТребуем погасить задолженность.");
        assertThat(response.generatedClaim().manualReviewRequired()).isTrue();
    }

    @Test
    void preservesJsonResponseContract() throws Exception {
        byte[] json = """
            {
              "success": true,
              "status": "SUCCESS",
              "generated_claim": {
                "claim_text": "Текст из JSON",
                "manual_review_required": false
              }
            }
            """.getBytes();

        AiGenerateClaimResponse response = decoder.decode(
            json,
            MediaType.APPLICATION_JSON,
            null
        );

        assertThat(response.generatedClaim().claimText())
            .isEqualTo("Текст из JSON");
        assertThat(response.generatedClaim().manualReviewRequired()).isFalse();
    }
}
