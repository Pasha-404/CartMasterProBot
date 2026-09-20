package org.cartmaster.bot;

import org.cartmaster.service.ShoppingListService;
import org.cartmaster.telegram.TelegramApiClient;
import org.cartmaster.telegram.TelegramApiFailure;
import org.cartmaster.telegram.TelegramApiRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingListMessagePresenterTest {

    private ShoppingListService shoppingListService;
    private FakeTelegramApiClient telegramApiClient;
    private ShoppingListMessagePresenter presenter;

    @BeforeEach
    void setUp() {
        shoppingListService = new ShoppingListService();
        telegramApiClient = new FakeTelegramApiClient();
        presenter = new ShoppingListMessagePresenter(
                shoppingListService,
                new ProductIconResolver(),
                telegramApiClient
        );
    }

    @Test
    void deletesSourceOnlyAfterTheListMessageIsConfirmed() {
        presenter.addProducts(1L, 91, "Молоко");

        FakeTelegramApiClient.Call send = telegramApiClient.only(SendMessage.class);
        assertThat(telegramApiClient.count(DeleteMessage.class)).isZero();

        send.complete(message(1L, 77));

        DeleteMessage delete = (DeleteMessage) telegramApiClient.only(DeleteMessage.class).request();
        assertThat(delete.getChatId()).isEqualTo("1");
        assertThat(delete.getMessageId()).isEqualTo(91);
        assertThat(shoppingListService.getActiveList(1L).messageId()).isEqualTo(77);
    }

    @Test
    void keepsSourceWhenPublicationFails() {
        presenter.addProducts(1L, 91, "Молоко");

        telegramApiClient.only(SendMessage.class)
                .fail(new TelegramApiRequestException(TelegramApiFailure.TRANSIENT));

        assertThat(telegramApiClient.count(DeleteMessage.class)).isZero();
        assertThat(shoppingListService.getSnapshot(1L).toBuy())
                .extracting(ShoppingListService.ShoppingListItem::name)
                .containsExactly("Молоко");
    }

    @Test
    void keepsTheWholeSourceWhenOnlyPartOfItFitsTheList() {
        shoppingListService.addProducts(1L, products(19));
        ShoppingListService.ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);

        presenter.addProducts(1L, 91, "Молоко, Хлеб");

        EditMessageText edit = (EditMessageText) telegramApiClient.only(EditMessageText.class).request();
        assertThat(edit.getText()).contains("⚠️");
        telegramApiClient.only(EditMessageText.class).complete(message(1L, 77));

        assertThat(telegramApiClient.count(DeleteMessage.class)).isZero();
        assertThat(shoppingListService.getSnapshot(1L).toBuy()).hasSize(20);
    }

    @Test
    void retainsLongNamesAndUsesASafeCompactDisplayLabel() {
        String longName = "Сыр творожный с зеленью и чесноком упаковка 400 граммов";

        presenter.addProducts(1L, 91, longName);

        SendMessage message = (SendMessage) telegramApiClient.only(SendMessage.class).request();
        assertThat(buttonTexts(message).get(0)).contains("…");
        assertThat(shoppingListService.getSnapshot(1L).toBuy())
                .extracting(ShoppingListService.ShoppingListItem::name)
                .containsExactly(longName);
        assertThat(hasUnpairedSurrogate(ShoppingListMessagePresenter.truncateForDisplay(
                "x".repeat(29) + "😀" + "хвост"
        ))).isFalse();
    }

    @Test
    void keepsOnePublicationWhileInitialMessageIsPending() {
        presenter.addProducts(1L, 91, "Молоко");
        presenter.addProducts(1L, 92, "Хлеб");

        assertThat(telegramApiClient.count(SendMessage.class)).isEqualTo(1);
        telegramApiClient.only(SendMessage.class).complete(message(1L, 77));

        EditMessageText edit = (EditMessageText) telegramApiClient.only(EditMessageText.class).request();
        assertThat(buttonTexts(edit)).contains("🥛 Молоко", "🍞 Хлеб", "🗂️ Новый список");
        telegramApiClient.only(EditMessageText.class).complete(message(1L, 77));

        assertThat(telegramApiClient.count(SendMessage.class)).isEqualTo(1);
        assertThat(telegramApiClient.count(DeleteMessage.class)).isEqualTo(2);
    }

    @Test
    void finalizationCannotBeOverwrittenByAnOlderInteractiveEdit() {
        shoppingListService.addProducts(1L, "Молоко, Хлеб");
        ShoppingListService.ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);
        List<ShoppingListService.ShoppingListItem> items = shoppingListService.getSnapshot(1L).toBuy();

        presenter.moveToBought(1L, 77, items.get(0).id());
        presenter.moveToBought(1L, 77, items.get(1).id());

        assertThat(telegramApiClient.count(EditMessageText.class)).isEqualTo(1);
        telegramApiClient.only(EditMessageText.class).complete(message(1L, 77));

        EditMessageText finalEdit = (EditMessageText) telegramApiClient.latest(EditMessageText.class).request();
        assertThat(buttonTexts(finalEdit)).isEmpty();
        telegramApiClient.latest(EditMessageText.class).complete(message(1L, 77));

        SendMessage newList = (SendMessage) telegramApiClient.only(SendMessage.class).request();
        assertThat(buttonTexts(newList)).containsExactly("🗂️ Новый список");
    }

    @Test
    void recreatesTheListAfterTheActiveMessageWasDeleted() {
        shoppingListService.addProducts(1L, "Молоко");
        ShoppingListService.ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);

        presenter.addProducts(1L, 91, "Хлеб");
        telegramApiClient.only(EditMessageText.class)
                .fail(new TelegramApiRequestException(TelegramApiFailure.MISSING_MESSAGE));

        FakeTelegramApiClient.Call replacement = telegramApiClient.only(SendMessage.class);
        replacement.complete(message(1L, 88));

        assertThat(shoppingListService.getActiveList(1L).messageId()).isEqualTo(88);
        assertThat(((DeleteMessage) telegramApiClient.only(DeleteMessage.class).request()).getMessageId()).isEqualTo(91);
    }

    @Test
    void deliversCapacityNoticeAfterInitialPublicationCompletes() {
        presenter.addProducts(1L, 91, products(20));
        presenter.addProducts(1L, 92, "Лишний товар");

        assertThat(telegramApiClient.count(SendMessage.class)).isEqualTo(1);
        telegramApiClient.only(SendMessage.class).complete(message(1L, 77));

        EditMessageText noticeEdit = (EditMessageText) telegramApiClient.only(EditMessageText.class).request();
        assertThat(noticeEdit.getText()).contains("⚠️");
    }

    @Test
    void archivedListWithLongHtmlNamesFitsTelegramMessageLimit() {
        shoppingListService.addProducts(1L, String.join(",", java.util.Collections.nCopies(20, "&".repeat(500))));
        ShoppingListService.ActiveListSnapshot activeList = shoppingListService.getActiveList(1L);
        shoppingListService.registerActiveMessage(1L, activeList.listId(), 77);

        presenter.startNewList(1L, 77);

        EditMessageText archive = (EditMessageText) telegramApiClient.only(EditMessageText.class).request();
        assertThat(archive.getText()).hasSizeLessThanOrEqualTo(4_096);
        assertThat(buttonTexts(archive)).isEmpty();
    }

    private String products(int count) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            names.add("Товар" + index);
        }
        return String.join(",", names);
    }

    private Message message(long chatId, int messageId) {
        Chat chat = new Chat();
        chat.setId(chatId);
        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(messageId);
        return message;
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

    private boolean hasUnpairedSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(character)) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeTelegramApiClient implements TelegramApiClient {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public CompletableFuture<Message> send(SendMessage message) {
            return futureFor(add(message));
        }

        @Override
        public CompletableFuture<Message> edit(EditMessageText message) {
            return futureFor(add(message));
        }

        @Override
        public CompletableFuture<Boolean> delete(DeleteMessage message) {
            return futureFor(add(message));
        }

        private Call add(BotApiMethod<?> request) {
            Call call = new Call(request);
            calls.add(call);
            return call;
        }

        private long count(Class<?> requestType) {
            return calls.stream().filter(call -> requestType.isInstance(call.request())).count();
        }

        private Call only(Class<?> requestType) {
            List<Call> matching = calls.stream().filter(call -> requestType.isInstance(call.request())).toList();
            assertThat(matching).hasSize(1);
            return matching.get(0);
        }

        private Call latest(Class<?> requestType) {
            return calls.stream()
                    .filter(call -> requestType.isInstance(call.request()))
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }

        @SuppressWarnings("unchecked")
        private <T> CompletableFuture<T> futureFor(Call call) {
            return (CompletableFuture<T>) (CompletableFuture<?>) call.future();
        }

        private static final class Call {
            private final BotApiMethod<?> request;
            private final CompletableFuture<Object> future = new CompletableFuture<>();

            private Call(BotApiMethod<?> request) {
                this.request = request;
            }

            private BotApiMethod<?> request() {
                return request;
            }

            private CompletableFuture<Object> future() {
                return future;
            }

            private void complete(Object value) {
                future.complete(value);
            }

            private void fail(Throwable failure) {
                future.completeExceptionally(failure);
            }
        }
    }
}
