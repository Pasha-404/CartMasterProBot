package org.cartmaster;

import org.cartmaster.bot.CartMasterProBot;
import org.cartmaster.telegram.TelegramApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = CartMasterProBotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "telegrambots.bot-username=CartMasterProBot",
                "telegrambots.bot-token=test-token",
                "telegrambots.bot-path=/webhook",
                "telegrambots.webhook-secret=test-secret"
        }
)
class CartMasterProBotApplicationTest {

    @Autowired
    private CartMasterProBot bot;

    @Autowired
    private TelegramApiClient telegramApiClient;

    @Test
    void startsWithSingleBotAndTelegramClientBeans() {
        assertThat(bot).isNotNull();
        assertThat(telegramApiClient).isNotNull();
    }
}
