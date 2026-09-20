package org.cartmaster.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cartmaster.config.BotConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;

import javax.net.ssl.SSLParameters;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@Component
public class TelegramHttpApiClient implements TelegramApiClient {

    private static final String TELEGRAM_API_URL = "https://api.telegram.org";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final String botToken;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public TelegramHttpApiClient(BotConfig config, ObjectMapper objectMapper) {
        this(config.getBotToken(), objectMapper, createRestClient(TELEGRAM_API_URL));
    }

    TelegramHttpApiClient(String botToken, ObjectMapper objectMapper, RestClient restClient) {
        this.botToken = botToken;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public CompletableFuture<Message> send(SendMessage message) {
        return execute(message, Message.class);
    }

    @Override
    public CompletableFuture<Message> edit(EditMessageText message) {
        return execute(message, Message.class);
    }

    @Override
    public CompletableFuture<Boolean> delete(DeleteMessage message) {
        return execute(message, Boolean.class);
    }

    static HttpClient createHttpClient() {
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslParameters(sslParameters)
                .build();
    }

    static RestClient createRestClient(String baseUrl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(createHttpClient());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private <T> CompletableFuture<T> execute(BotApiMethod<?> method, Class<T> resultType) {
        return CompletableFuture.supplyAsync(() -> {
            TelegramApiResponse response;
            try {
                response = restClient.post()
                        .uri("/bot{token}/{method}", botToken, method.getMethod())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(method)
                        .retrieve()
                        .body(TelegramApiResponse.class);
            } catch (RestClientException exception) {
                throw new TelegramApiRequestException(TelegramApiFailure.TRANSIENT);
            }

            if (response == null || !response.ok()) {
                throw new TelegramApiRequestException(classifyFailure(response));
            }
            return objectMapper.convertValue(response.result(), resultType);
        });
    }

    private TelegramApiFailure classifyFailure(TelegramApiResponse response) {
        if (response == null) {
            return TelegramApiFailure.UNKNOWN;
        }
        if (response.errorCode() != null && response.errorCode() >= 500) {
            return TelegramApiFailure.TRANSIENT;
        }

        String description = response.description() == null
                ? ""
                : response.description().toLowerCase(Locale.ROOT);
        if (description.contains("message is not modified")) {
            return TelegramApiFailure.NOT_MODIFIED;
        }
        if (description.contains("message to edit not found")
                || description.contains("message can't be edited")) {
            return TelegramApiFailure.MISSING_MESSAGE;
        }
        if (response.errorCode() != null && response.errorCode() == 403) {
            return TelegramApiFailure.FORBIDDEN;
        }
        return TelegramApiFailure.UNKNOWN;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TelegramApiResponse(
            boolean ok,
            JsonNode result,
            @JsonProperty("error_code") Integer errorCode,
            String description
    ) {
    }
}
