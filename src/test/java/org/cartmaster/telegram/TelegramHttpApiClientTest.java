package org.cartmaster.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramHttpApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTelegramJsonAndMapsTheMessageResult() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        TelegramHttpApiClient client = startClient(exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            writeJson(exchange, 200, """
                    {"ok":true,"result":{"message_id":77,"chat":{"id":1,"type":"group"}}}
                    """);
        });

        SendMessage request = new SendMessage();
        request.setChatId("1");
        request.setText("Молоко");
        Message result = client.send(request).get();

        assertThat(requestPath.get()).isEqualTo("/bottest-token/sendmessage");
        assertThat(requestBody.get().path("chat_id").asText()).isEqualTo("1");
        assertThat(requestBody.get().path("text").asText()).isEqualTo("Молоко");
        assertThat(result.getMessageId()).isEqualTo(77);
    }

    @Test
    void classifiesMissingMessageResponseWithoutKeepingProviderDetails() {
        TelegramHttpApiClient client = startClient(exchange -> writeJson(exchange, 200, """
                {"ok":false,"error_code":400,"description":"Bad Request: message to edit not found"}
                """));
        EditMessageText request = new EditMessageText();
        request.setChatId("1");
        request.setMessageId(77);
        request.setText("Список");

        assertThatThrownBy(() -> client.edit(request).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TelegramApiRequestException.class)
                .satisfies(exception -> assertThat(((TelegramApiRequestException) exception.getCause()).getFailure())
                        .isEqualTo(TelegramApiFailure.MISSING_MESSAGE));
    }

    @Test
    void enablesHttpsEndpointIdentification() {
        assertThat(TelegramHttpApiClient.createHttpClient().sslParameters().getEndpointIdentificationAlgorithm())
                .isEqualTo("HTTPS");
    }

    private TelegramHttpApiClient startClient(ResponseHandler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                try {
                    handler.handle(exchange);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            return new TelegramHttpApiClient(
                    "test-token",
                    objectMapper,
                    TelegramHttpApiClient.createRestClient(baseUrl)
            );
        } catch (IOException exception) {
            throw new AssertionError("Could not start local Telegram API fixture", exception);
        }
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ResponseHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
