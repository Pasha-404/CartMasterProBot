package org.cartmaster.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotConfigTest {

    @Test
    void acceptsCompleteConfiguration() {
        BotConfig config = validConfig();

        assertThatCode(config::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankWebhookSecret() {
        BotConfig config = validConfig();
        config.setWebhookSecret(" ");

        assertThatThrownBy(config::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("telegrambots.webhook-secret");
    }

    @Test
    void rejectsPathWithoutLeadingSlash() {
        BotConfig config = validConfig();
        config.setBotPath("webhook");

        assertThatThrownBy(config::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must start with '/'");
    }

    private BotConfig validConfig() {
        BotConfig config = new BotConfig();
        config.setBotUsername("CartMasterProBot");
        config.setBotToken("123456:test-token");
        config.setBotPath("/webhook");
        config.setWebhookSecret("test-secret");
        return config;
    }
}
