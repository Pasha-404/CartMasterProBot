package org.cartmaster.bot;

import org.cartmaster.config.BotConfig;
import org.cartmaster.service.ShoppingListService;
import org.cartmaster.service.ShoppingListService.ActiveListSnapshot;
import org.cartmaster.service.ShoppingListService.ShoppingListSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
        bot = new CartMasterProBot(config, shoppingListService, new ProductIconResolver());
    }

    @Test
    void updatesOneActiveListMessageWhenNewProductsArrive() {
        CartMasterProBot textBot = spy(bot);
        blockListSending(textBot);

        assertThat(textBot.onWebhookUpdateReceived(textUpdate(1L, "Молоко"))).isNull();
        ArgumentCaptor<SendMessage> firstMessage = ArgumentCaptor.forClass(SendMessage.class);
        verify(textBot).sendActiveListMessage(
                anyLong(),
                anyString(),
                any(ShoppingListSnapshot.class),
                firstMessage.capture()
        );

        ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);
        doNothing().when(textBot).editShoppingListMessage(any(EditMessageText.class));

        assertThat(textBot.onWebhookUpdateReceived(textUpdate(1L, "Хлеб"))).isNull();
        ArgumentCaptor<EditMessageText> updatedMessage = ArgumentCaptor.forClass(EditMessageText.class);
        verify(textBot).editShoppingListMessage(updatedMessage.capture());

        assertThat(buttonTexts(firstMessage.getValue())).contains("🥛 Молоко", "🗂️ Новый список");
        assertThat(buttonTexts(updatedMessage.getValue())).contains("🥛 Молоко", "🍞 Хлеб", "🗂️ Новый список");
        assertThat(shoppingListService.getActiveList(1L).messageId()).isEqualTo(77);
    }

    @Test
    void escapesBoughtProductNamesInHtmlMessage() {
        shoppingListService.addProducts(1L, "<молоко> & хлеб");
        ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);
        String productId = shoppingListService.getSnapshot(1L).toBuy().get(0).id();
        shoppingListService.moveToBought(1L, productId);

        CartMasterProBot textBot = spy(bot);
        doNothing().when(textBot).editShoppingListMessage(any(EditMessageText.class));

        assertThat(textBot.onWebhookUpdateReceived(textUpdate(1L, " "))).isNull();
        ArgumentCaptor<EditMessageText> message = ArgumentCaptor.forClass(EditMessageText.class);
        verify(textBot).editShoppingListMessage(message.capture());

        assertThat(message.getValue().getParseMode()).isEqualTo("HTML");
        assertThat(message.getValue().getText()).contains("&lt;молоко&gt; &amp; хлеб");
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
    void completesTheOldMessageAndOpensANewListAfterTheLastProduct() {
        shoppingListService.addProducts(1L, "Молоко");
        ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);
        String productId = shoppingListService.getSnapshot(1L).toBuy().get(0).id();
        CartMasterProBot callbackBot = spy(bot);
        runAfterFinalEdit(callbackBot);
        blockListSending(callbackBot);

        BotApiMethod<?> response = callbackBot.onWebhookUpdateReceived(callbackUpdate(1L, 77, "callback-id", productId));

        assertThat(response).isInstanceOf(AnswerCallbackQuery.class);
        assertThat(((AnswerCallbackQuery) response).getCallbackQueryId()).isEqualTo("callback-id");
        ArgumentCaptor<EditMessageText> completedMessage = ArgumentCaptor.forClass(EditMessageText.class);
        verify(callbackBot).editShoppingListMessageThen(completedMessage.capture(), any(Runnable.class));
        assertThat(completedMessage.getValue().getText()).contains("🥛 Молоко");
        assertThat(buttonTexts(completedMessage.getValue())).isEmpty();

        ArgumentCaptor<SendMessage> newListMessage = ArgumentCaptor.forClass(SendMessage.class);
        verify(callbackBot).sendActiveListMessage(
                anyLong(),
                anyString(),
                any(ShoppingListSnapshot.class),
                newListMessage.capture()
        );
        assertThat(newListMessage.getValue().getText()).contains("<i>пусто</i>");
        assertThat(buttonTexts(newListMessage.getValue())).containsExactly("🗂️ Новый список");
        assertThat(shoppingListService.getSnapshot(1L).toBuy()).isEmpty();
        assertThat(shoppingListService.getSnapshot(1L).bought()).isEmpty();
    }

    @Test
    void newListButtonFreezesTheCurrentMessageAndSendsAnotherOne() {
        shoppingListService.addProducts(1L, "Молоко, Хлеб");
        ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);
        CartMasterProBot callbackBot = spy(bot);
        runAfterFinalEdit(callbackBot);
        blockListSending(callbackBot);

        BotApiMethod<?> response = callbackBot.onWebhookUpdateReceived(callbackUpdate(1L, 77, "callback-id", "/new"));

        assertThat(response).isInstanceOf(AnswerCallbackQuery.class);
        ArgumentCaptor<EditMessageText> previousMessage = ArgumentCaptor.forClass(EditMessageText.class);
        verify(callbackBot).editShoppingListMessageThen(previousMessage.capture(), any(Runnable.class));
        assertThat(previousMessage.getValue().getText()).contains("🥛 Молоко", "🍞 Хлеб");
        assertThat(buttonTexts(previousMessage.getValue())).isEmpty();
        assertThat(shoppingListService.getSnapshot(1L).toBuy()).isEmpty();
    }

    @Test
    void addsProductIconsWithoutChangingTheOriginalProductName() {
        CartMasterProBot textBot = spy(bot);
        blockListSending(textBot);

        assertThat(textBot.onWebhookUpdateReceived(textUpdate(1L, "МОЛОКО 3,2%, неизвестный товар"))).isNull();
        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(textBot).sendActiveListMessage(
                anyLong(),
                anyString(),
                any(ShoppingListSnapshot.class),
                message.capture()
        );

        assertThat(buttonTexts(message.getValue())).contains("🥛 МОЛОКО 3,2%", "неизвестный товар");
        assertThat(shoppingListService.getSnapshot(1L).toBuy())
                .extracting(ShoppingListService.ShoppingListItem::name)
                .containsExactlyInAnyOrder("МОЛОКО 3,2%", "неизвестный товар");
    }

    @Test
    void keepsTheRenderedListWithinTelegramMessageLimit() {
        String products = IntStream.range(0, ShoppingListService.MAX_PRODUCTS_PER_LIST)
                .mapToObj(index -> "&".repeat(ShoppingListService.MAX_PRODUCT_NAME_LENGTH))
                .collect(Collectors.joining(","));
        shoppingListService.addProducts(1L, products);
        CartMasterProBot textBot = spy(bot);
        blockListSending(textBot);

        assertThat(textBot.onWebhookUpdateReceived(textUpdate(1L, " "))).isNull();
        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(textBot).sendActiveListMessage(
                anyLong(),
                anyString(),
                any(ShoppingListSnapshot.class),
                message.capture()
        );

        assertThat(message.getValue().getText()).hasSizeLessThanOrEqualTo(4_096);
    }

    @Test
    void returnsNullForNullUpdate() {
        assertThat(bot.onWebhookUpdateReceived(null)).isNull();
    }

    private void blockListSending(CartMasterProBot target) {
        doNothing().when(target).sendActiveListMessage(
                anyLong(),
                anyString(),
                any(ShoppingListSnapshot.class),
                any(SendMessage.class)
        );
    }

    private void runAfterFinalEdit(CartMasterProBot target) {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(target).editShoppingListMessageThen(any(EditMessageText.class), any(Runnable.class));
    }

    private List<String> buttonTexts(SendMessage message) {
        return buttonTexts((InlineKeyboardMarkup) message.getReplyMarkup());
    }

    private List<String> buttonTexts(EditMessageText message) {
        return buttonTexts(message.getReplyMarkup());
    }

    private List<String> buttonTexts(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .map(button -> button.getText())
                .toList();
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
