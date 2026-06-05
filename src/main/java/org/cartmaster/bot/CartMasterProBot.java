package org.cartmaster.bot;

import org.cartmaster.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.*;

@Component
public class CartMasterProBot extends TelegramWebhookBot {

    private final BotConfig config;
    private static final Logger logger = LoggerFactory.getLogger(CartMasterProBot.class);

    private static final int MAX_PRODUCT_LENGTH = 30;

    public CartMasterProBot(BotConfig config) {
        super(config.getBotToken());
        this.config = config;
    }

    private static class Product {
        String name;
        String id;

        Product(String name) {
            this.name = name.trim();
            this.id = UUID.nameUUIDFromBytes(this.name.getBytes()).toString();
        }
    }

    private static class UserLists {
        List<Product> toBuyList = new ArrayList<>();
        List<Product> boughtList = new ArrayList<>();
    }

    private final Map<Long, UserLists> userListsMap = new HashMap<>();

    @Override
    public SendMessage onWebhookUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                return handleTextMessage(update.getMessage().getChatId(), update.getMessage().getText());
            } else if (update.hasCallbackQuery()) {
                return handleCallbackQuery(update);
            } else if (update.hasChannelPost() && update.getChannelPost().hasText()) {
                return handleTextMessage(update.getChannelPost().getChatId(), update.getChannelPost().getText());
            }
        } catch (Exception e) {
            logger.error("Ошибка при обработке update", e);
            return sendErrorMessage(update);
        }
        return null;
    }

    private SendMessage handleTextMessage(Long chatId, String text) {
        text = text.trim();

        if (text.startsWith("/")) {
            return handleCommand(chatId, text);
        }

        // Поддержка ввода списка через переносы строк и запятые
        String[] rawLines = text.split("\\R"); // \n, \r, \r\n и пр.
        for (String line : rawLines) {
            // Разделяем каждую строку по запятым (учитывая возможные пробелы)
            String[] items = line.split("\\s*,\\s*");
            for (String item : items) {
                String product = item.trim();
                if (!product.isEmpty()) {
                    addProduct(chatId, product);
                }
            }
        }

        return showShoppingList(chatId);
    }


    private SendMessage handleCommand(long chatId, String command) {
        String pureCommand = command.split("@")[0]; // убираем @CartMasterPro_Bot, если есть

        switch (pureCommand) {
            case "/start":
                userListsMap.put(chatId, new UserLists());
                return sendMessage(chatId, "🛒 Привет! Просто отправь мне названия продуктов, и я добавлю их в список.");
            case "/clear":
                userListsMap.remove(chatId);
                return sendMessage(chatId, "🗂️ Новый список создан. Начнём заново!");
            default:
                return sendMessage(chatId, "Неизвестная команда");
        }
    }

    private SendMessage handleCallbackQuery(Update update) {
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        String callbackData = update.getCallbackQuery().getData();

        try {
            if ("/clear".equals(callbackData)) {
                userListsMap.remove(chatId);
                return sendMessage(chatId, "🗂️ Новый список создан. Начнём заново!");
            }

            moveToBoughtList(chatId, callbackData);
            return showShoppingList(chatId);

        } catch (Exception e) {
            logger.error("Ошибка обработки callback", e);
            return sendMessage(chatId, "⚠️ Произошла ошибка, попробуйте ещё раз");
        }
    }

    private void addProduct(long userId, String productName) {
        if (productName == null || productName.trim().isEmpty()) return;

        productName = productName.trim(); // лишнее, но пусть будет для надёжности

        Product product = new Product(productName);
        userListsMap.computeIfAbsent(userId, k -> new UserLists()).toBuyList.add(product);
    }

    private void moveToBoughtList(long chatId, String productId) {
        UserLists lists = userListsMap.get(chatId);
        if (lists == null) return;

        Optional<Product> productOpt = lists.toBuyList.stream()
                .filter(p -> p.id.equals(productId))
                .findFirst();

        productOpt.ifPresent(product -> {
            lists.toBuyList.remove(product);
            lists.boughtList.add(product);
        });
    }

    private SendMessage showShoppingList(long chatId) {
        UserLists lists = userListsMap.getOrDefault(chatId, new UserLists());

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));

        StringBuilder text = new StringBuilder("✅ *Купленные:*\n");
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (lists.boughtList.isEmpty()) {
            text.append("_пусто_\n");
        } else {
            for (Product product : lists.boughtList) {
                String label = "✔️ " + truncateProductName(product.name);
                text.append(label).append("\n");
            }
        }

        text.append("\n\n🛒 *Надо купить:*\n");

        if (lists.toBuyList.isEmpty()) {
            text.append("_пусто_\n");
        } else {
            List<Product> sortedList = new ArrayList<>(lists.toBuyList);
            sortedList.sort(Comparator.comparing(p -> p.name.toLowerCase()));

            for (Product product : sortedList) {
                String display = truncateProductName(product.name);
                InlineKeyboardButton button = new InlineKeyboardButton(display);
                button.setCallbackData(product.id);
                rows.add(Collections.singletonList(button));
            }
        }

        InlineKeyboardButton clearButton = new InlineKeyboardButton("🗂️ Новый список");
        clearButton.setCallbackData("/clear");
        rows.add(Collections.singletonList(clearButton));

        message.setText(text.toString());
        message.setParseMode("Markdown");
        keyboard.setKeyboard(rows);
        message.setReplyMarkup(keyboard);

        return message;
    }

    private String truncateProductName(String name) {
        return name.length() > MAX_PRODUCT_LENGTH
                ? name.substring(0, MAX_PRODUCT_LENGTH) + "..."
                : name;
    }

    private SendMessage sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        return message;
    }

    private SendMessage sendErrorMessage(Update update) {
        try {
            Long chatId = update.hasMessage()
                    ? update.getMessage().getChatId()
                    : update.getCallbackQuery().getMessage().getChatId();
            return sendMessage(chatId, "⚠️ Произошла ошибка, попробуйте ещё раз");
        } catch (Exception e) {
            logger.error("Ошибка при отправке сообщения об ошибке", e);
            return null;
        }
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
