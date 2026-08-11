package org.cartmaster.controller;

import org.cartmaster.bot.CartMasterProBot;
import org.cartmaster.config.BotConfig;
import org.cartmaster.service.ProcessedUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WebhookControllerTest {

    private CartMasterProBot bot;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        bot = mock(CartMasterProBot.class);
        BotConfig config = new BotConfig();
        config.setWebhookSecret("test-secret");
        controller = new WebhookController(bot, config, new ProcessedUpdateService());
    }

    @Test
    void ignoresRequestWithWrongSecret() {
        Update update = new Update();

        ResponseEntity<BotApiMethod<?>> response = controller.onUpdateReceived(update, "wrong-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(bot);
    }

    @Test
    void ignoresRequestWithoutSecret() {
        Update update = new Update();

        ResponseEntity<BotApiMethod<?>> response = controller.onUpdateReceived(update, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(bot);
    }

    @Test
    void forwardsRequestWithValidSecret() {
        Update update = new Update();
        BotApiMethod<?> answer = new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
        doReturn(answer).when(bot).onWebhookUpdateReceived(update);

        ResponseEntity<BotApiMethod<?>> response = controller.onUpdateReceived(update, "test-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(answer);
        verify(bot).onWebhookUpdateReceived(update);
    }

    @Test
    void ignoresAnUpdateDeliveredTwice() {
        Update update = new Update();
        update.setUpdateId(123);
        BotApiMethod<?> answer = new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
        doReturn(answer).when(bot).onWebhookUpdateReceived(update);

        ResponseEntity<BotApiMethod<?>> firstResponse = controller.onUpdateReceived(update, "test-secret");
        ResponseEntity<BotApiMethod<?>> secondResponse = controller.onUpdateReceived(update, "test-secret");

        assertThat(firstResponse.getBody()).isSameAs(answer);
        assertThat(secondResponse.getBody()).isNull();
        verify(bot).onWebhookUpdateReceived(update);
    }
}
