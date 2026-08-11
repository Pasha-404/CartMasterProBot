package org.cartmaster.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "telegrambots")
public class BotConfig implements InitializingBean {
    private String botUsername;
    private String botToken;
    private String botPath;
    private String webhookSecret;

    public String getBotUsername() {
        return botUsername;
    }

    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername;
    }

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public String getBotPath() {
        return botPath;
    }

    public void setBotPath(String botPath) {
        this.botPath = botPath;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    public void afterPropertiesSet() {
        requireText(botUsername, "telegrambots.bot-username");
        requireText(botToken, "telegrambots.bot-token");
        requireText(botPath, "telegrambots.bot-path");
        requireText(webhookSecret, "telegrambots.webhook-secret");

        if (!botPath.startsWith("/")) {
            throw new IllegalStateException("telegrambots.bot-path must start with '/'");
        }
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured");
        }
    }
}
