package org.cartmaster.bot;

import org.cartmaster.service.ShoppingListService;
import org.cartmaster.service.ShoppingListService.ActiveListSnapshot;
import org.cartmaster.service.ShoppingListService.AddProductsResult;
import org.cartmaster.service.ShoppingListService.ListTransition;
import org.cartmaster.service.ShoppingListService.MoveToBoughtResult;
import org.cartmaster.service.ShoppingListService.ShoppingListItem;
import org.cartmaster.service.ShoppingListService.ShoppingListSnapshot;
import org.cartmaster.telegram.TelegramApiClient;
import org.cartmaster.telegram.TelegramApiFailure;
import org.cartmaster.telegram.TelegramApiRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.text.BreakIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ShoppingListMessagePresenter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShoppingListMessagePresenter.class);

    private static final int MAX_PRODUCT_DISPLAY_LENGTH = 30;

    private final ShoppingListService shoppingListService;
    private final ProductIconResolver productIconResolver;
    private final TelegramApiClient telegramApiClient;
    private final ConcurrentMap<Long, ChatPresentationState> states = new ConcurrentHashMap<>();

    public ShoppingListMessagePresenter(
            ShoppingListService shoppingListService,
            ProductIconResolver productIconResolver,
            TelegramApiClient telegramApiClient
    ) {
        this.shoppingListService = shoppingListService;
        this.productIconResolver = productIconResolver;
        this.telegramApiClient = telegramApiClient;
    }

    public void addProducts(long chatId, Integer sourceMessageId, String input) {
        ChatPresentationState state = stateFor(chatId);
        RenderOperation operation;
        synchronized (state) {
            AddProductsResult result = shoppingListService.addProducts(chatId, input);
            if (result.addedProducts() == 0 && !result.hasRejectedProducts()) {
                return;
            }

            ActiveListSnapshot activeList = shoppingListService.getActiveList(chatId);
            if (sourceMessageId != null && result.addedProducts() > 0 && !result.hasRejectedProducts()) {
                state.pendingSourceDeletes.add(new PendingSourceDelete(
                        chatId,
                        activeList.listId(),
                        sourceMessageId,
                        result.addedProductIds()
                ));
            }
            if (result.hasRejectedProducts()) {
                state.pendingNotices.add(buildAddProductsNotice());
            }
            state.renderRequested = true;
            state.waitingForNewEvent = false;
            operation = takeNextLocked(chatId, state);
        }
        dispatch(operation);
    }

    public void moveToBought(long chatId, int messageId, String productId) {
        ChatPresentationState state = stateFor(chatId);
        RenderOperation operation;
        synchronized (state) {
            MoveToBoughtResult result = shoppingListService.moveToBought(chatId, messageId, productId);
            if (!result.moved()) {
                return;
            }
            if (result.startsNewList()) {
                state.pendingFinalizations.add(result.transition());
            }
            state.renderRequested = true;
            state.waitingForNewEvent = false;
            operation = takeNextLocked(chatId, state);
        }
        dispatch(operation);
    }

    public void startNewList(long chatId, int messageId) {
        ChatPresentationState state = stateFor(chatId);
        RenderOperation operation;
        synchronized (state) {
            ListTransition transition = shoppingListService.startNewList(chatId, messageId);
            if (transition == null) {
                return;
            }
            state.pendingFinalizations.add(transition);
            state.renderRequested = true;
            state.waitingForNewEvent = false;
            operation = takeNextLocked(chatId, state);
        }
        dispatch(operation);
    }

    public void reset(long chatId) {
        ChatPresentationState state = stateFor(chatId);
        RenderOperation operation;
        synchronized (state) {
            ActiveListSnapshot previousList = shoppingListService.getActiveList(chatId);
            shoppingListService.reset(chatId);
            state.pendingSourceDeletes.clear();
            state.pendingNotices.clear();
            state.renderRequested = false;
            state.waitingForNewEvent = false;
            if (previousList.messageId() != null) {
                state.pendingRetirements.add(new ArchivedMessage(previousList.messageId(), previousList.snapshot()));
            }
            operation = takeNextLocked(chatId, state);
        }
        dispatch(operation);
    }

    private ChatPresentationState stateFor(long chatId) {
        return states.computeIfAbsent(chatId, ignored -> new ChatPresentationState());
    }

    private RenderOperation takeNextLocked(long chatId, ChatPresentationState state) {
        if (state.inFlight != null || state.waitingForNewEvent) {
            return null;
        }

        ArchivedMessage retirement = state.pendingRetirements.poll();
        if (retirement != null) {
            return state.start(new RenderOperation(
                    OperationType.RETIRE,
                    chatId,
                    null,
                    retirement.messageId(),
                    retirement.snapshot(),
                    List.of()
            ));
        }

        ListTransition finalization = state.pendingFinalizations.poll();
        if (finalization != null) {
            return state.start(new RenderOperation(
                    OperationType.FINALIZE,
                    chatId,
                    null,
                    finalization.previousMessageId(),
                    finalization.previousSnapshot(),
                    List.of()
            ));
        }

        if (!state.renderRequested) {
            return null;
        }

        ActiveListSnapshot activeList = shoppingListService.getActiveList(chatId);
        state.renderRequested = false;
        List<String> notices = List.copyOf(state.pendingNotices);
        OperationType type = activeList.messageId() == null ? OperationType.SEND_ACTIVE : OperationType.EDIT_ACTIVE;
        return state.start(new RenderOperation(
                type,
                chatId,
                activeList.listId(),
                activeList.messageId(),
                activeList.snapshot(),
                notices
        ));
    }

    private void dispatch(RenderOperation operation) {
        if (operation == null) {
            return;
        }

        try {
            switch (operation.type()) {
                case SEND_ACTIVE -> telegramApiClient.send(createShoppingListMessage(
                        operation.chatId(), operation.snapshot(), true, operation.notices()
                )).whenComplete((message, failure) -> complete(operation, message, failure));
                case EDIT_ACTIVE -> telegramApiClient.edit(createShoppingListEdit(
                        operation.chatId(), operation.messageId(), operation.snapshot(), true, operation.notices()
                )).whenComplete((message, failure) -> complete(operation, null, failure));
                case FINALIZE, RETIRE -> telegramApiClient.edit(createShoppingListEdit(
                        operation.chatId(), operation.messageId(), operation.snapshot(), false, List.of()
                )).whenComplete((message, failure) -> complete(operation, null, failure));
            }
        } catch (RuntimeException exception) {
            complete(operation, null, exception);
        }
    }

    private void complete(RenderOperation operation, Message sentMessage, Throwable failure) {
        ChatPresentationState state = stateFor(operation.chatId());
        List<PendingSourceDelete> deletes = List.of();
        Throwable resolvedFailure = unwrap(failure);
        RenderOperation next;

        synchronized (state) {
            if (state.inFlight != operation) {
                return;
            }
            state.inFlight = null;

            if (resolvedFailure != null && failureKind(resolvedFailure) == TelegramApiFailure.NOT_MODIFIED) {
                resolvedFailure = null;
            }

            if (resolvedFailure == null) {
                if (operation.type() == OperationType.SEND_ACTIVE) {
                    if (sentMessage == null || sentMessage.getMessageId() == null) {
                        resolvedFailure = new TelegramApiRequestException(TelegramApiFailure.UNKNOWN);
                    } else {
                        ActiveListSnapshot registered = shoppingListService.registerActiveMessage(
                                operation.chatId(), operation.listId(), sentMessage.getMessageId()
                        );
                        if (registered == null) {
                            state.pendingRetirements.add(new ArchivedMessage(
                                    sentMessage.getMessageId(), operation.snapshot()
                            ));
                        } else {
                            state.pendingNotices.removeAll(operation.notices());
                            deletes = takeConfirmedSourceDeletes(state, operation.listId(), operation.snapshot());
                        }
                    }
                } else if (operation.type() == OperationType.EDIT_ACTIVE) {
                    ActiveListSnapshot activeList = shoppingListService.getActiveList(operation.chatId());
                    if (operation.listId().equals(activeList.listId())
                            && operation.messageId().equals(activeList.messageId())) {
                        state.pendingNotices.removeAll(operation.notices());
                        deletes = takeConfirmedSourceDeletes(state, operation.listId(), operation.snapshot());
                    }
                }
            }

            if (resolvedFailure != null) {
                handleFailureLocked(operation, state, resolvedFailure);
            }
            next = takeNextLocked(operation.chatId(), state);
        }

        if (resolvedFailure != null) {
            LOGGER.warn("Telegram operation {} failed: {}", operation.type(), failureKind(resolvedFailure));
        }
        deletes.forEach(this::deleteSourceMessage);
        dispatch(next);
    }

    private void handleFailureLocked(
            RenderOperation operation,
            ChatPresentationState state,
            Throwable failure
    ) {
        TelegramApiFailure kind = failureKind(failure);
        if (operation.type() == OperationType.EDIT_ACTIVE && kind == TelegramApiFailure.MISSING_MESSAGE) {
            ActiveListSnapshot invalidated = shoppingListService.invalidateActiveMessage(
                    operation.chatId(), operation.listId(), operation.messageId()
            );
            if (invalidated != null) {
                state.renderRequested = true;
                return;
            }
        }

        if (operation.type() == OperationType.FINALIZE || operation.type() == OperationType.RETIRE) {
            return;
        }

        state.renderRequested = true;
        state.waitingForNewEvent = true;
    }

    private List<PendingSourceDelete> takeConfirmedSourceDeletes(
            ChatPresentationState state,
            String listId,
            ShoppingListSnapshot snapshot
    ) {
        Set<String> visibleProductIds = new java.util.HashSet<>();
        snapshot.toBuy().forEach(item -> visibleProductIds.add(item.id()));
        snapshot.bought().forEach(item -> visibleProductIds.add(item.id()));

        List<PendingSourceDelete> confirmed = new ArrayList<>();
        Iterator<PendingSourceDelete> iterator = state.pendingSourceDeletes.iterator();
        while (iterator.hasNext()) {
            PendingSourceDelete pending = iterator.next();
            if (pending.listId().equals(listId) && visibleProductIds.containsAll(pending.productIds())) {
                confirmed.add(pending);
                iterator.remove();
            }
        }
        return confirmed;
    }

    private void deleteSourceMessage(PendingSourceDelete pending) {
        DeleteMessage message = new DeleteMessage();
        message.setChatId(pending.chatId());
        message.setMessageId(pending.messageId());
        try {
            telegramApiClient.delete(message).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn("Telegram operation DELETE_SOURCE failed: {}", failureKind(unwrap(failure)));
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.warn("Telegram operation DELETE_SOURCE failed: {}", failureKind(exception));
        }
    }

    private SendMessage createShoppingListMessage(
            long chatId,
            ShoppingListSnapshot snapshot,
            boolean interactive,
            List<String> notices
    ) {
        ShoppingListView view = createShoppingListView(snapshot, interactive);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(withNotices(view.text(), notices));
        message.setParseMode("HTML");
        message.setReplyMarkup(view.keyboard());
        return message;
    }

    private EditMessageText createShoppingListEdit(
            long chatId,
            int messageId,
            ShoppingListSnapshot snapshot,
            boolean interactive,
            List<String> notices
    ) {
        ShoppingListView view = createShoppingListView(snapshot, interactive);
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setMessageId(messageId);
        message.setText(withNotices(view.text(), notices));
        message.setParseMode("HTML");
        message.setReplyMarkup(view.keyboard());
        return message;
    }

    private ShoppingListView createShoppingListView(ShoppingListSnapshot snapshot, boolean interactive) {
        StringBuilder text = new StringBuilder("✅ <b>Купленные:</b>\n");
        appendProducts(text, snapshot.bought(), "✔️ ", "<i>пусто</i>");

        text.append("\n🛒 <b>Надо купить:</b>\n");
        if (snapshot.toBuy().isEmpty()) {
            text.append("<i>пусто</i>\n");
        } else if (!interactive) {
            appendProducts(text, snapshot.toBuy(), "• ", "<i>пусто</i>");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (interactive) {
            for (ShoppingListItem product : snapshot.toBuy()) {
                InlineKeyboardButton button = new InlineKeyboardButton(formatProductName(product.name()));
                button.setCallbackData(product.id());
                rows.add(Collections.singletonList(button));
            }
            InlineKeyboardButton newListButton = new InlineKeyboardButton("🗂️ Новый список");
            newListButton.setCallbackData("/new");
            rows.add(Collections.singletonList(newListButton));
        }

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return new ShoppingListView(text.toString(), keyboard);
    }

    private void appendProducts(
            StringBuilder text,
            List<ShoppingListItem> products,
            String prefix,
            String emptyText
    ) {
        if (products.isEmpty()) {
            text.append(emptyText).append('\n');
            return;
        }
        for (ShoppingListItem product : products) {
            text.append(prefix)
                    .append(escapeHtml(formatProductName(product.name())))
                    .append('\n');
        }
    }

    private String buildAddProductsNotice() {
        return "⚠️ Часть товаров не добавлена: в одном списке может быть не больше "
                + ShoppingListService.MAX_PRODUCTS_PER_LIST + " товаров.";
    }

    private String withNotices(String listText, List<String> notices) {
        return notices.isEmpty() ? listText : String.join("\n", notices) + "\n\n" + listText;
    }

    private String formatProductName(String name) {
        return productIconResolver.decorate(truncateForDisplay(name));
    }

    static String truncateForDisplay(String name) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(name);
        int boundary = iterator.first();
        for (int displayed = 0; displayed < MAX_PRODUCT_DISPLAY_LENGTH; displayed++) {
            int nextBoundary = iterator.next();
            if (nextBoundary == BreakIterator.DONE) {
                return name;
            }
            boundary = nextBoundary;
        }
        return iterator.next() == BreakIterator.DONE ? name : name.substring(0, boundary) + "…";
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private TelegramApiFailure failureKind(Throwable failure) {
        return failure instanceof TelegramApiRequestException telegramFailure
                ? telegramFailure.getFailure()
                : TelegramApiFailure.UNKNOWN;
    }

    private enum OperationType {
        SEND_ACTIVE,
        EDIT_ACTIVE,
        FINALIZE,
        RETIRE
    }

    private record RenderOperation(
            OperationType type,
            long chatId,
            String listId,
            Integer messageId,
            ShoppingListSnapshot snapshot,
            List<String> notices
    ) {
    }

    private record PendingSourceDelete(long chatId, String listId, int messageId, List<String> productIds) {
        private PendingSourceDelete {
            productIds = List.copyOf(productIds);
        }
    }

    private record ArchivedMessage(int messageId, ShoppingListSnapshot snapshot) {
    }

    private record ShoppingListView(String text, InlineKeyboardMarkup keyboard) {
    }

    private static final class ChatPresentationState {
        private final LinkedHashSet<String> pendingNotices = new LinkedHashSet<>();
        private final List<PendingSourceDelete> pendingSourceDeletes = new ArrayList<>();
        private final ArrayDeque<ListTransition> pendingFinalizations = new ArrayDeque<>();
        private final ArrayDeque<ArchivedMessage> pendingRetirements = new ArrayDeque<>();
        private RenderOperation inFlight;
        private boolean renderRequested;
        private boolean waitingForNewEvent;

        private RenderOperation start(RenderOperation operation) {
            inFlight = operation;
            return operation;
        }
    }
}
