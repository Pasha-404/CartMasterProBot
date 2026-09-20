package org.cartmaster.bot;

import org.cartmaster.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class CartMasterProBot {

    private static final Logger LOGGER = LoggerFactory.getLogger(CartMasterProBot.class);

    private static final String NEW_LIST_CALLBACK = "/new";
    private static final String RESET_MESSAGE = "🗂️ Новый список создан. Начнём заново!";
    private static final String ERROR_MESSAGE = "⚠️ Произошла ошибка, попробуйте ещё раз";

    private final BotConfig config;
    private final ShoppingListMessagePresenter messagePresenter;

    public CartMasterProBot(BotConfig config, ShoppingListMessagePresenter messagePresenter) {
        this.config = config;
        this.messagePresenter = messagePresenter;
    }

    public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
        if (update == null) {
            return null;
        }

        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                return handleTextMessage(update.getMessage());
            }
            if (update.hasCallbackQuery()) {
                return handleCallbackQuery(update.getCallbackQuery());
            }
            if (update.hasChannelPost() && update.getChannelPost().hasText()) {
                return handleTextMessage(update.getChannelPost());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Ошибка при обработке Telegram update", exception);
            return sendErrorMessage(update);
        }
        return null;
    }

    private BotApiMethod<?> handleTextMessage(Message message) {
        long chatId = message.getChatId();
        String normalizedText = message.getText() == null ? "" : message.getText().strip();
        if (normalizedText.startsWith("/")) {
            return handleCommand(chatId, normalizedText);
        }

        messagePresenter.addProducts(chatId, message.getMessageId(), normalizedText);
        return null;
    }

    private BotApiMethod<?> handleCommand(long chatId, String commandLine) {
        Command command = Command.parse(commandLine);
        if (command.targetUsername() != null && !command.targetUsername().equalsIgnoreCase(config.getBotUsername())) {
            return null;
        }

        return switch (command.name()) {
            case "/start" -> {
                messagePresenter.reset(chatId);
                yield sendMessage(
                        chatId,
                        "🛒 Привет! Просто отправь мне названия продуктов, и я добавлю их в список."
                );
            }
            case "/clear" -> {
                messagePresenter.reset(chatId);
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
            long chatId = callbackMessage.getChatId();
            if (NEW_LIST_CALLBACK.equals(callbackQuery.getData())) {
                messagePresenter.startNewList(chatId, callbackMessage.getMessageId());
            } else {
                messagePresenter.moveToBought(chatId, callbackMessage.getMessageId(), callbackQuery.getData());
            }
        }

        String callbackId = callbackQuery.getId();
        return callbackId == null || callbackId.isBlank() ? null : new AnswerCallbackQuery(callbackId);
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

    private record Command(String name, String targetUsername) {
        private static Command parse(String commandLine) {
            String token = commandLine.split("\\s+", 2)[0];
            int mentionIndex = token.indexOf('@');
            if (mentionIndex < 0) {
                return new Command(token, null);
            }
            String targetUsername = token.substring(mentionIndex + 1);
            return new Command(token.substring(0, mentionIndex), targetUsername.isBlank() ? "" : targetUsername);
        }
    }
}
