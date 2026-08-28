package org.cartmaster.bot;

import org.cartmaster.config.BotConfig;
import org.cartmaster.service.ShoppingListService;
import org.cartmaster.service.ShoppingListService.ActiveListSnapshot;
import org.cartmaster.service.ShoppingListService.AddProductsResult;
import org.cartmaster.service.ShoppingListService.ListTransition;
import org.cartmaster.service.ShoppingListService.MoveToBoughtResult;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CartMasterProBot extends TelegramWebhookBot {

    private static final Logger LOGGER = LoggerFactory.getLogger(CartMasterProBot.class);

    private static final int MAX_PRODUCT_DISPLAY_LENGTH = 30;
    private static final String NEW_LIST_CALLBACK = "/new";
    private static final String RESET_MESSAGE = "🗂️ Новый список создан. Начнём заново!";
    private static final String ERROR_MESSAGE = "⚠️ Произошла ошибка, попробуйте ещё раз";

    private final BotConfig config;
    private final ShoppingListService shoppingListService;
    private final ProductIconResolver productIconResolver;
    private final ConcurrentMap<ListMessageKey, Boolean> pendingListMessages = new ConcurrentHashMap<>();

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

    private BotApiMethod<?> handleTextMessage(long chatId, String text) {
        String normalizedText = text == null ? "" : text.strip();
        if (normalizedText.startsWith("/")) {
            return handleCommand(chatId, normalizedText);
        }

        AddProductsResult result = shoppingListService.addProducts(chatId, normalizedText);
        refreshActiveList(chatId, buildAddProductsNotice(result));
        return null;
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
            updateListAfterCallback(
                    callbackMessage.getChatId(),
                    callbackMessage.getMessageId(),
                    callbackQuery.getData()
            );
        }

        String callbackId = callbackQuery.getId();
        return callbackId == null || callbackId.isBlank() ? null : new AnswerCallbackQuery(callbackId);
    }

    private void updateListAfterCallback(long chatId, int messageId, String callbackData) {
        if (NEW_LIST_CALLBACK.equals(callbackData)) {
            ListTransition transition = shoppingListService.startNewList(chatId, messageId);
            if (transition != null) {
                finalizeListAndOpenNew(chatId, transition);
            }
            return;
        }

        MoveToBoughtResult result = shoppingListService.moveToBought(chatId, messageId, callbackData);
        if (!result.moved()) {
            return;
        }
        if (result.startsNewList()) {
            finalizeListAndOpenNew(chatId, result.transition());
            return;
        }
        editShoppingListMessage(createShoppingListEdit(chatId, messageId, result.snapshot(), true, null));
    }

    private void refreshActiveList(long chatId, String notice) {
        ActiveListSnapshot activeList = shoppingListService.getActiveList(chatId);
        if (activeList.messageId() != null) {
            editShoppingListMessage(createShoppingListEdit(
                    chatId,
                    activeList.messageId(),
                    activeList.snapshot(),
                    true,
                    notice
            ));
            return;
        }

        ListMessageKey key = new ListMessageKey(chatId, activeList.listId());
        if (pendingListMessages.putIfAbsent(key, Boolean.TRUE) == null) {
            sendActiveListMessage(
                    chatId,
                    activeList.listId(),
                    activeList.snapshot(),
                    createShoppingListMessage(chatId, activeList.snapshot(), true, notice)
            );
        }
    }

    private void finalizeListAndOpenNew(long chatId, ListTransition transition) {
        EditMessageText finalMessage = createShoppingListEdit(
                chatId,
                transition.previousMessageId(),
                transition.previousSnapshot(),
                false,
                null
        );
        editShoppingListMessageThen(finalMessage, () -> refreshActiveList(chatId, null));
    }

    private SendMessage createShoppingListMessage(
            long chatId,
            ShoppingListSnapshot snapshot,
            boolean interactive,
            String notice
    ) {
        ShoppingListView view = createShoppingListView(snapshot, interactive);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(withNotice(view.text(), notice));
        message.setParseMode("HTML");
        message.setReplyMarkup(view.keyboard());
        return message;
    }

    private EditMessageText createShoppingListEdit(
            long chatId,
            int messageId,
            ShoppingListSnapshot snapshot,
            boolean interactive,
            String notice
    ) {
        ShoppingListView view = createShoppingListView(snapshot, interactive);
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setMessageId(messageId);
        message.setText(withNotice(view.text(), notice));
        message.setParseMode("HTML");
        message.setReplyMarkup(view.keyboard());
        return message;
    }

    private ShoppingListView createShoppingListView(ShoppingListSnapshot snapshot, boolean interactive) {
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
        } else if (!interactive) {
            for (ShoppingListItem product : snapshot.toBuy()) {
                text.append("• ")
                        .append(escapeHtml(formatProductName(product.name())))
                        .append('\n');
            }
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (interactive) {
            for (ShoppingListItem product : snapshot.toBuy()) {
                InlineKeyboardButton button = new InlineKeyboardButton(formatProductName(product.name()));
                button.setCallbackData(product.id());
                rows.add(Collections.singletonList(button));
            }

            InlineKeyboardButton newListButton = new InlineKeyboardButton("🗂️ Новый список");
            newListButton.setCallbackData(NEW_LIST_CALLBACK);
            rows.add(Collections.singletonList(newListButton));
        }

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);

        return new ShoppingListView(text.toString(), keyboard);
    }

    private String withNotice(String listText, String notice) {
        return notice == null ? listText : notice + "\n\n" + listText;
    }

    void sendActiveListMessage(
            long chatId,
            String listId,
            ShoppingListSnapshot sentSnapshot,
            SendMessage message
    ) {
        ListMessageKey key = new ListMessageKey(chatId, listId);
        try {
            executeAsync(message).whenComplete((sentMessage, exception) -> {
                pendingListMessages.remove(key);
                if (exception != null) {
                    LOGGER.warn("Не удалось отправить сообщение со списком", exception);
                    return;
                }
                if (sentMessage == null || sentMessage.getMessageId() == null) {
                    LOGGER.warn("Telegram не вернул идентификатор сообщения со списком");
                    return;
                }

                ActiveListSnapshot activeList = shoppingListService.registerActiveMessage(
                        chatId,
                        listId,
                        sentMessage.getMessageId()
                );
                if (activeList != null && !activeList.snapshot().equals(sentSnapshot)) {
                    refreshActiveList(chatId, null);
                }
            });
        } catch (TelegramApiException exception) {
            pendingListMessages.remove(key);
            LOGGER.warn("Не удалось отправить сообщение со списком", exception);
        }
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

    void editShoppingListMessageThen(EditMessageText message, Runnable afterEdit) {
        try {
            executeAsync(message).whenComplete((ignored, exception) -> {
                if (exception != null) {
                    LOGGER.warn("Не удалось завершить сообщение со списком", exception);
                }
                afterEdit.run();
            });
        } catch (TelegramApiException exception) {
            LOGGER.warn("Не удалось завершить сообщение со списком", exception);
            afterEdit.run();
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

    private String formatProductName(String name) {
        return productIconResolver.decorate(truncateProductName(name));
    }

    private String truncateProductName(String name) {
        return name.length() > MAX_PRODUCT_DISPLAY_LENGTH
                ? name.substring(0, MAX_PRODUCT_DISPLAY_LENGTH) + "…"
                : name;
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

    private record ListMessageKey(long chatId, String listId) {
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
