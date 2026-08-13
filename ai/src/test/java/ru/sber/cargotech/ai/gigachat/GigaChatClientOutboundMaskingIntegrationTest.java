package ru.sber.cargotech.ai.gigachat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.llm.logging.LlmLogService;
import ru.sber.cargotech.ai.security.ReversiblePromptMasker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GigaChatClientOutboundMaskingIntegrationTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("__CTP_\\d{3}__");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOnlyMaskedSensitiveValuesToProviderAndRestoresResponse() throws Exception {
        AtomicReference<String> providerBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> handleChat(exchange, providerBody));
        server.start();

        GigaChatProperties properties = new GigaChatProperties();
        properties.setChatUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
        properties.setChatModel("GigaChat-2-Pro");
        properties.setTemperature(0.1);
        properties.setMaxTokens(1000);

        GigaChatAuthService authService = mock(GigaChatAuthService.class);
        when(authService.getAccessToken()).thenReturn("test-token");

        LlmLogService logService = mock(LlmLogService.class);
        when(logService.newRequestId()).thenReturn("req-mask-test");
        when(logService.now()).thenReturn(Instant.parse("2026-08-13T04:00:00Z"));

        GigaChatClient client = new GigaChatClient(
                properties,
                authService,
                RestClient.builder(),
                logService,
                new ReversiblePromptMasker()
        );

        UUID actorUserId = UUID.fromString("f1d15888-d170-493d-a463-51ceaf64c6a3");

        var response = client.sendChatWithTrace(
                List.of(
                        new GigaChatMessage("system", "Сформируй юридическую претензию."),
                        new GigaChatMessage(
                                "user",
                                """
                                {
                                  "creditor":{"name":"ООО Экспедитор","inn":"7701001123","legal_address":"г. Санкт-Петербург, ул. Ленина, 1"},
                                  "debtor":{"name":"ООО Альфа Тест","inn":"7705123456","legal_address":"г. Москва, ул. Тестовая, д. 10"},
                                  "signatory":{"name":"Дмитриев Павел Алексеевич"},
                                  "contract_number":"АН-ТЭ/2026-013",
                                  "amount":"250 000 рублей 00 копеек",
                                  "law":"ст. 395 ГК РФ"
                                }
                                """
                        )
                ),
                "claim-mask-test",
                actorUserId,
                "GENERATE_CLAIM_PAYMENT_DELAY"
        );

        String outbound = providerBody.get();
        assertThat(outbound).isNotBlank();
        assertThat(outbound)
                .contains("__CTP_")
                .contains("АН-ТЭ/2026-013")
                .contains("250 000 рублей 00 копеек")
                .contains("ст. 395 ГК РФ")
                .doesNotContain("ООО Экспедитор")
                .doesNotContain("ООО Альфа Тест")
                .doesNotContain("7701001123")
                .doesNotContain("7705123456")
                .doesNotContain("Санкт-Петербург")
                .doesNotContain("Тестовая")
                .doesNotContain("Дмитриев Павел Алексеевич");

        assertThat(response.response().firstContent()).contains("ООО Экспедитор");

        verify(logService).ensureWithinLimits("claim-mask-test");
        verify(logService).logSuccess(
                eq("req-mask-test"),
                eq("claim-mask-test"),
                eq(actorUserId),
                eq("GENERATE_CLAIM_PAYMENT_DELAY"),
                eq("GigaChat"),
                eq("GigaChat-2-Pro"),
                anyList(),
                any(),
                any()
        );
    }

    private void handleChat(HttpExchange exchange, AtomicReference<String> providerBody) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        providerBody.set(body);

        Matcher matcher = PLACEHOLDER.matcher(body);
        if (!matcher.find()) {
            byte[] error = "{\"error\":\"placeholder missing\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
            return;
        }

        String placeholder = matcher.group();
        String json = """
                {
                  "choices":[
                    {
                      "index":0,
                      "message":{
                        "role":"assistant",
                        "content":"Отправитель: %s"
                      }
                    }
                  ],
                  "usage":{
                    "prompt_tokens":100,
                    "completion_tokens":20,
                    "total_tokens":120
                  }
                }
                """.formatted(placeholder);

        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
