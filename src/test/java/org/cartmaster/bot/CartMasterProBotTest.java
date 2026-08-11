package org.cartmaster.bot;

import org.cartmaster.config.BotConfig;
import org.cartmaster.service.ShoppingListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class CartMasterProBotTest {

    private ShoppingListService shoppingListService;
    private CartMasterProBot bot;

    @BeforeEach
    void setUp() {
        BotConfig config = new BotConfig();
        config.setBotUsername("CartMasterProBot");
        config.setBotToken("123456:test-token");
        config.setBotPath("/webhook");
        config.setWebhookSecret("test-secret");
        shoppingListService = new ShoppingListService();
        bot = new CartMasterProBot(config, shoppingListService);
    }

    @Test
    void escapesProductNamesInHtmlMessage() {
        shoppingListService.addProducts(1L, "<молоко> & хлеб");
        String productId = shoppingListService.getSnapshot(1L).toBuy().get(0).id();
        shoppingListService.moveToBought(1L, productId);

        BotApiMethod<?> response = bot.onWebhookUpdateReceived(textUpdate(1L, " "));

        assertThat(response).isInstanceOf(SendMessage.class);
        SendMessage message = (SendMessage) response;
        assertThat(message.getParseMode()).isEqualTo("HTML");
        assertThat(message.getText()).contains("&lt;молоко&gt; &amp; хлеб");
    }

    @Test
    void commandWithBotMentionAndArgumentsResetsList() {
        shoppingListService.addProducts(1L, "Молоко");

        BotApiMethod<?> response = bot.onWebhookUpdateReceived(
                textUpdate(1L, "/start@CartMasterProBot payload")
        );

        assertThat(response).isInstanceOf(SendMessage.class);
        assertThat(((SendMessage) response).getText()).contains("Привет");
        assertThat(shoppingListService.getSnapshot(1L).toBuy()).isEmpty();
    }

    @Test
    void answersCallbackAndEditsTheExistingShoppingListMessage() {
        shoppingListService.addProducts(1L, "Молоко");
        String productId = shoppingListService.getSnapshot(1L).toBuy().get(0).id();
        CartMasterProBot callbackBot = spy(bot);
        doNothing().when(callbackBot).editShoppingListMessage(any(EditMessageText.class));

        Message message = mock(Message.class);
        when(message.getChatId()).thenReturn(1L);
        when(message.getMessageId()).thenReturn(77);

        CallbackQuery callbackQuery = mock(CallbackQuery.class);
        when(callbackQuery.getId()).thenReturn("callback-id");
        when(callbackQuery.getData()).thenReturn(productId);
        when(callbackQuery.getMessage()).thenReturn(message);

        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery()).thenReturn(callbackQuery);

        BotApiMethod<?> response = callbackBot.onWebhookUpdateReceived(update);

        assertThat(response).isInstanceOf(AnswerCallbackQuery.class);
        assertThat(((AnswerCallbackQuery) response).getCallbackQueryId()).isEqualTo("callback-id");
        assertThat(shoppingListService.getSnapshot(1L).toBuy()).isEmpty();
        assertThat(shoppingListService.getSnapshot(1L).bought()).hasSize(1);
        org.mockito.Mockito.verify(callbackBot).editShoppingListMessage(any(EditMessageText.class));
    }

    @Test
    void keepsTheRenderedListWithinTelegramMessageLimit() {
        String products = IntStream.range(0, ShoppingListService.MAX_PRODUCTS_PER_LIST)
                .mapToObj(index -> "&".repeat(ShoppingListService.MAX_PRODUCT_NAME_LENGTH))
                .collect(Collectors.joining(","));
        shoppingListService.addProducts(1L, products);

        BotApiMethod<?> response = bot.onWebhookUpdateReceived(textUpdate(1L, " "));

        assertThat(response).isInstanceOf(SendMessage.class);
        assertThat(((SendMessage) response).getText()).hasSizeLessThanOrEqualTo(4_096);
    }

    @Test
    void returnsNullForNullUpdate() {
        assertThat(bot.onWebhookUpdateReceived(null)).isNull();
    }

    private Update textUpdate(long chatId, String text) {
        Message message = mock(Message.class);
        when(message.hasText()).thenReturn(true);
        when(message.getChatId()).thenReturn(chatId);
        when(message.getText()).thenReturn(text);

        Update update = mock(Update.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        return update;
    }
}
