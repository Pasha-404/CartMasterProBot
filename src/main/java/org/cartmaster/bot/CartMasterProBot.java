package org.cartmaster.bot;

import org.cartmaster.config.BotConfig;
import org.cartmaster.service.ShoppingListService;
import org.cartmaster.service.ShoppingListService.AddProductsResult;
import org.cartmaster.service.ShoppingListService.ShoppingListItem;
import org.cartmaster.service.ShoppingListService.ShoppingListSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CartMasterProBot extends TelegramWebhookBot {

    private static final Logger LOGGER = LoggerFactory.getLogger(CartMasterProBot.class);

    private static final int MAX_PRODUCT_DISPLAY_LENGTH = 30;
    private static final String CLEAR_CALLBACK = "/clear";
    private static final String RESET_MESSAGE = "🗂️ Новый список создан. Начнём заново!";
    private static final String ERROR_MESSAGE = "⚠️ Произошла ошибка, попробуйте ещё раз";

    private final BotConfig config;
    private final ShoppingListService shoppingListService;
    private final ProductIconResolver productIconResolver;

    public CartMasterProBot(
            BotConfig config,
            ShoppingListService shoppingListService,
            ProductIconResolver productIconResolver
    ) {
        super(config.getBotToken());
        this.config = config;
        this.shoppingListService = shoppingListService;
        this.productIconResolver = productIconResolver;
    }

    @Override
    public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
        if (update == null) {
            return null;
        }

        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                return handleTextMessage(update.getMessage().getChatId(), update.getMessage().getText());
            }
            if (update.hasCallbackQuery()) {
                return handleCallbackQuery(update.getCallbackQuery());
            }
            if (update.hasChannelPost() && update.getChannelPost().hasText()) {
                return handleTextMessage(update.getChannelPost().getChatId(), update.getChannelPost().getText());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Ошибка при обработке Telegram update", exception);
            return sendErrorMessage(update);
        }
        return null;
    }

    private SendMessage handleTextMessage(long chatId, String text) {
        String normalizedText = text == null ? "" : text.strip();
        if (normalizedText.startsWith("/")) {
            return handleCommand(chatId, normalizedText);
        }

        AddProductsResult result = shoppingListService.addProducts(chatId, normalizedText);
        return showShoppingList(chatId, buildAddProductsNotice(result));
    }

    private SendMessage handleCommand(long chatId, String commandLine) {
        String command = commandLine.split("\\s+", 2)[0];
        int mentionIndex = command.indexOf('@');
        if (mentionIndex >= 0) {
            command = command.substring(0, mentionIndex);
        }

        return switch (command) {
            case "/start" -> {
                shoppingListService.reset(chatId);
                yield sendMessage(
                        chatId,
                        "🛒 Привет! Просто отправь мне названия продуктов, и я добавлю их в список."
                );
            }
            case "/clear" -> {
                shoppingListService.reset(chatId);
                yield sendMessage(chatId, RESET_MESSAGE);
            }
            default -> sendMessage(chatId, "Неизвестная команда");
        };
    }

    private BotApiMethod<?> handleCallbackQuery(CallbackQuery callbackQuery) {
        if (callbackQuery == null) {
            return null;
        }

        MaybeInaccessibleMessage callbackMessage = callbackQuery.getMessage();
        if (callbackMessage != null
                && callbackMessage.getChatId() != null
                && callbackMessage.getMessageId() != null) {
            updateListAfterCallback(callbackQuery, callbackMessage);
        }

        String callbackId = callbackQuery.getId();
        return callbackId == null || callbackId.isBlank() ? null : new AnswerCallbackQuery(callbackId);
    }

    private void updateListAfterCallback(
            CallbackQuery callbackQuery,
            MaybeInaccessibleMessage callbackMessage
    ) {
        long chatId = callbackMessage.getChatId();
        String callbackData = callbackQuery.getData();
        if (CLEAR_CALLBACK.equals(callbackData)) {
            shoppingListService.reset(chatId);
            editShoppingListMessage(createShoppingListEdit(chatId, callbackMessage.getMessageId()));
            return;
        }

        if (shoppingListService.moveToBoughtAndResetWhenCompleted(chatId, callbackData)) {
            editShoppingListMessage(createShoppingListEdit(chatId, callbackMessage.getMessageId()));
        }
    }

    private SendMessage showShoppingList(long chatId) {
        return showShoppingList(chatId, null);
    }

    private SendMessage showShoppingList(long chatId, String notice) {
        ShoppingListView view = createShoppingListView(chatId);
        String text = notice == null ? view.text() : notice + "\n\n" + view.text();

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("HTML");
        message.setReplyMarkup(view.keyboard());
        return message;
    }

    private EditMessageText createShoppingListEdit(long chatId, int messageId) {
        ShoppingListView view = createShoppingListView(chatId);

        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setMessageId(messageId);
        message.setText(view.text());
        message.setParseMode("HTML");
        message.setReplyMarkup(view.keyboard());
        return message;
    }

    private ShoppingListView createShoppingListView(long chatId) {
        ShoppingListSnapshot snapshot = shoppingListService.getSnapshot(chatId);
        StringBuilder text = new StringBuilder("✅ <b>Купленные:</b>\n");

        if (snapshot.bought().isEmpty()) {
            text.append("<i>пусто</i>\n");
        } else {
            for (ShoppingListItem product : snapshot.bought()) {
                text.append("✔️ ")
                        .append(escapeHtml(formatProductName(product.name())))
                        .append('\n');
            }
        }

        text.append("\n🛒 <b>Надо купить:</b>\n");
        if (snapshot.toBuy().isEmpty()) {
            text.append("<i>пусто</i>\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ShoppingListItem product : snapshot.toBuy()) {
            InlineKeyboardButton button = new InlineKeyboardButton(formatProductName(product.name()));
            button.setCallbackData(product.id());
            rows.add(Collections.singletonList(button));
        }

        InlineKeyboardButton clearButton = new InlineKeyboardButton("🗂️ Новый список");
        clearButton.setCallbackData(CLEAR_CALLBACK);
        rows.add(Collections.singletonList(clearButton));

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);

        return new ShoppingListView(text.toString(), keyboard);
    }

    void editShoppingListMessage(EditMessageText message) {
        try {
            executeAsync(message).exceptionally(exception -> {
                LOGGER.warn("Не удалось обновить сообщение со списком", exception);
                return null;
            });
        } catch (TelegramApiException exception) {
            LOGGER.warn("Не удалось отправить запрос на обновление списка", exception);
        }
    }

    private String buildAddProductsNotice(AddProductsResult result) {
        if (!result.hasRejectedProducts()) {
            return null;
        }

        List<String> reasons = new ArrayList<>();
        if (result.rejectedTooLong() > 0) {
            reasons.add("название длиннее " + ShoppingListService.MAX_PRODUCT_NAME_LENGTH + " символов");
        }
        if (result.rejectedByListLimit() > 0) {
            reasons.add("в одном списке может быть не больше "
                    + ShoppingListService.MAX_PRODUCTS_PER_LIST + " товаров");
        }
        return "⚠️ Часть товаров не добавлена: " + String.join("; ", reasons) + ".";
    }

    private String truncateProductName(String name) {
        return name.length() > MAX_PRODUCT_DISPLAY_LENGTH
                ? name.substring(0, MAX_PRODUCT_DISPLAY_LENGTH) + "…"
                : name;
    }

    private String formatProductName(String name) {
        return productIconResolver.decorate(truncateProductName(name));
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private SendMessage sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        return message;
    }

    private SendMessage sendErrorMessage(Update update) {
        Long chatId = resolveChatId(update);
        return chatId == null ? null : sendMessage(chatId, ERROR_MESSAGE);
    }

    private Long resolveChatId(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        if (update.hasChannelPost()) {
            return update.getChannelPost().getChatId();
        }
        return null;
    }

    private record ShoppingListView(String text, InlineKeyboardMarkup keyboard) {
    }

    @Override
    public String getBotUsername() {
        return config.getBotUsername();
    }

    @Override
    public String getBotToken() {
        return config.getBotToken();
    }

    @Override
    public String getBotPath() {
        return config.getBotPath();
    }
}
