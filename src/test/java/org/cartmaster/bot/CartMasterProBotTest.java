package org.cartmaster.bot;

import org.cartmaster.config.BotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CartMasterProBotTest {

    private ShoppingListMessagePresenter messagePresenter;
    private CartMasterProBot bot;

    @BeforeEach
    void setUp() {
        BotConfig config = new BotConfig();
        config.setBotUsername("CartMasterProBot");
        messagePresenter = mock(ShoppingListMessagePresenter.class);
        bot = new CartMasterProBot(config, messagePresenter);
    }

    @Test
    void routesOrdinaryTextToTheListPresenter() {
        BotApiMethod<?> response = bot.onWebhookUpdateReceived(textUpdate(1L, 91, "Молоко, Хлеб"));

        assertThat(response).isNull();
        verify(messagePresenter).addProducts(1L, 91, "Молоко, Хлеб");
    }

    @Test
    void ignoresCommandsAddressedToAnotherBot() {
        BotApiMethod<?> response = bot.onWebhookUpdateReceived(textUpdate(1L, 91, "/clear@AnotherBot"));

        assertThat(response).isNull();
        verifyNoInteractions(messagePresenter);
    }

    @Test
    void acceptsCaseInsensitiveCommandAddressedToThisBot() {
        BotApiMethod<?> response = bot.onWebhookUpdateReceived(textUpdate(1L, 91, "/clear@cartmasterprobot"));

        assertThat(response).isInstanceOf(SendMessage.class);
        assertThat(((SendMessage) response).getText()).contains("Новый список");
        verify(messagePresenter).reset(1L);
    }

    @Test
    void acknowledgesItemCallbacksAndRoutesThemToThePresenter() {
        BotApiMethod<?> response = bot.onWebhookUpdateReceived(callbackUpdate(1L, 77, "callback-id", "product-id"));

        assertThat(response).isInstanceOf(AnswerCallbackQuery.class);
        assertThat(((AnswerCallbackQuery) response).getCallbackQueryId()).isEqualTo("callback-id");
        verify(messagePresenter).moveToBought(1L, 77, "product-id");
    }

    @Test
    void startsNewListOnlyThroughTheDedicatedCallback() {
        BotApiMethod<?> response = bot.onWebhookUpdateReceived(callbackUpdate(1L, 77, "callback-id", "/new"));

        assertThat(response).isInstanceOf(AnswerCallbackQuery.class);
        verify(messagePresenter).startNewList(1L, 77);
    }

    @Test
    void returnsNullForNullUpdate() {
        assertThat(bot.onWebhookUpdateReceived(null)).isNull();
    }

    private Update textUpdate(long chatId, int messageId, String text) {
        Message message = mock(Message.class);
        when(message.hasText()).thenReturn(true);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getText()).thenReturn(text);

        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        return update;
    }

    private Update callbackUpdate(long chatId, int messageId, String callbackId, String callbackData) {
        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getMessageId()).thenReturn(messageId);

        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        when(callbackQuery.getId()).thenReturn(callbackId);
        when(callbackQuery.getData()).thenReturn(callbackData);
        when(callbackQuery.getMessage()).thenReturn(message);

        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);
        return update;
    }
}
