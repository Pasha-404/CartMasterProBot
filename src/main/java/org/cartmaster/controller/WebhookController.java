package org.cartmaster.controller;

import org.cartmaster.bot.CartMasterProBot;
import org.cartmaster.config.BotConfig;
import org.cartmaster.service.ProcessedUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
public class WebhookController {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final CartMasterProBot bot;
    private final BotConfig config;
    private final ProcessedUpdateService processedUpdateService;

    public WebhookController(
            CartMasterProBot bot,
            BotConfig config,
            ProcessedUpdateService processedUpdateService
    ) {
        this.bot = bot;
        this.config = config;
        this.processedUpdateService = processedUpdateService;
    }

    @PostMapping("${telegrambots.bot-path}")
    public ResponseEntity<BotApiMethod<?>> onUpdateReceived(
            @RequestBody Update update,
            @RequestHeader(value = SECRET_HEADER, required = false) String secretHeader
    ) {
        if (!hasValidSecret(secretHeader)) {
            return ResponseEntity.ok().build();
        }
        if (update == null || !processedUpdateService.markIfNew(update.getUpdateId())) {
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.ok(bot.onWebhookUpdateReceived(update));
    }

    private boolean hasValidSecret(String providedSecret) {
        if (providedSecret == null) {
            return false;
        }

        byte[] expected = config.getWebhookSecret().getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedSecret.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }
}
