package org.cartmaster.controller;

import org.cartmaster.bot.CartMasterProBot;
import org.cartmaster.config.BotConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Objects;

@RestController
public class WebhookController {

    private final CartMasterProBot bot;
    private final BotConfig config;

    @Autowired
    public WebhookController(CartMasterProBot bot, BotConfig config) {
        this.bot = bot;
        this.config = config;
    }

    @PostMapping("${telegrambots.bot-path}")
    public BotApiMethod<?> onUpdateReceived(
            @RequestBody Update update,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretHeader
    ) {
        // Если секрет в конфиге задан — строго проверяем заголовок;
        // если не задан — пропускаем (обратная совместимость).
        String expected = config.getWebhookSecret();
        if (expected != null && !expected.isBlank() && !Objects.equals(secretHeader, expected)) {
            // Чужой запрос — игнорируем (вернём 200 OK без тела).
            return null;
        }

        return bot.onWebhookUpdateReceived(update);
    }
}
