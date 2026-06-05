package org.cartmaster.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "telegrambots")
public class BotConfig {
    private String botUsername;
    private String botToken;
    private String botPath;

    /**
     * Секрет для проверки запросов Telegram к вебхуку.
     * Если пустой/null — проверка заголовка отключена (для обратной совместимости).
     */
    private String webhookSecret;
}
