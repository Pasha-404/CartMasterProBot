package org.cartmaster.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ShoppingListService {

    public static final int MAX_PRODUCT_NAME_LENGTH = 30;
    public static final int MAX_PRODUCTS_PER_LIST = 20;

    private final ConcurrentMap<Long, ChatLists> listsByChat = new ConcurrentHashMap<>();

    public void reset(long chatId) {
        listsByChat.put(chatId, new ChatLists());
    }

    public AddProductsResult addProducts(long chatId, String input) {
        List<String> productNames = parseProductNames(input);
        List<String> acceptedNames = new ArrayList<>();
        int rejectedTooLong = 0;
        for (String productName : productNames) {
            if (productName.length() > MAX_PRODUCT_NAME_LENGTH) {
                rejectedTooLong++;
            } else {
                acceptedNames.add(productName);
            }
        }

        ChatLists lists = getChatLists(chatId);
        return lists.addProducts(acceptedNames, rejectedTooLong);
    }

    public boolean moveToBought(long chatId, String productId) {
        ChatLists lists = listsByChat.get(chatId);
        return lists != null && lists.moveToBought(productId);
    }

    public MoveToBoughtResult moveToBought(long chatId, int messageId, String productId) {
        ChatLists lists = listsByChat.get(chatId);
        return lists == null
                ? MoveToBoughtResult.notMoved()
                : lists.moveToBought(messageId, productId);
    }

    public ListTransition startNewList(long chatId, int messageId) {
        return getChatLists(chatId).startNewList(messageId);
    }

    public ActiveListSnapshot getActiveList(long chatId) {
        return getChatLists(chatId).snapshot();
    }

    public ActiveListSnapshot registerActiveMessage(long chatId, String listId, int messageId) {
        ChatLists lists = listsByChat.get(chatId);
        return lists == null ? null : lists.registerMessage(listId, messageId);
    }

    public ShoppingListSnapshot getSnapshot(long chatId) {
        ChatLists lists = listsByChat.get(chatId);
        return lists == null ? ShoppingListSnapshot.empty() : lists.snapshot().snapshot();
    }

    private ChatLists getChatLists(long chatId) {
        return listsByChat.computeIfAbsent(chatId, ignored -> new ChatLists());
    }

    private static List<String> parseProductNames(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        for (String item : input.split("(?:\\R|,(?!\\d)|(?<!\\d),)")) {
            String name = item.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    public record ShoppingListItem(String id, String name) {
        private static ShoppingListItem create(String name) {
            return new ShoppingListItem(UUID.randomUUID().toString(), name);
        }
    }

    public record AddProductsResult(
            int addedProducts,
            int rejectedTooLong,
            int rejectedByListLimit
    ) {
        public boolean hasRejectedProducts() {
            return rejectedTooLong > 0 || rejectedByListLimit > 0;
        }
    }

    public record ShoppingListSnapshot(
            List<ShoppingListItem> toBuy,
            List<ShoppingListItem> bought
    ) {
        public ShoppingListSnapshot {
            toBuy = List.copyOf(toBuy);
            bought = List.copyOf(bought);
        }

        private static ShoppingListSnapshot empty() {
            return new ShoppingListSnapshot(List.of(), List.of());
        }
    }

    public record ActiveListSnapshot(
            String listId,
            Integer messageId,
            ShoppingListSnapshot snapshot
    ) {
    }

    public record ListTransition(
            int previousMessageId,
            ShoppingListSnapshot previousSnapshot,
            String newListId
    ) {
    }

    public record MoveToBoughtResult(
            boolean moved,
            ShoppingListSnapshot snapshot,
            ListTransition transition
    ) {
        private static MoveToBoughtResult notMoved() {
            return new MoveToBoughtResult(false, ShoppingListSnapshot.empty(), null);
        }

        public boolean startsNewList() {
            return transition != null;
        }
    }

    private static final class ChatLists {
        private String activeListId = createListId();
        private UserLists activeLists = new UserLists();
        private Integer activeMessageId;

        private synchronized AddProductsResult addProducts(List<String> names, int rejectedTooLong) {
            int addedProducts = activeLists.addProducts(names);
            return new AddProductsResult(addedProducts, rejectedTooLong, names.size() - addedProducts);
        }

        private synchronized boolean moveToBought(String productId) {
            return activeLists.moveToBought(productId);
        }

        private synchronized MoveToBoughtResult moveToBought(int messageId, String productId) {
            if (!isActiveMessage(messageId) || !activeLists.moveToBought(productId)) {
                return MoveToBoughtResult.notMoved();
            }

            ShoppingListSnapshot snapshot = activeLists.snapshot();
            if (!snapshot.toBuy().isEmpty()) {
                return new MoveToBoughtResult(true, snapshot, null);
            }

            ListTransition transition = startNewList();
            return new MoveToBoughtResult(true, snapshot, transition);
        }

        private synchronized ListTransition startNewList(int messageId) {
            return isActiveMessage(messageId) ? startNewList() : null;
        }

        private ListTransition startNewList() {
            ListTransition transition = new ListTransition(
                    activeMessageId,
                    activeLists.snapshot(),
                    createListId()
            );
            activeListId = transition.newListId();
            activeLists = new UserLists();
            activeMessageId = null;
            return transition;
        }

        private synchronized ActiveListSnapshot snapshot() {
            return new ActiveListSnapshot(activeListId, activeMessageId, activeLists.snapshot());
        }

        private synchronized ActiveListSnapshot registerMessage(String listId, int messageId) {
            if (!activeListId.equals(listId) || activeMessageId != null) {
                return null;
            }
            activeMessageId = messageId;
            return snapshot();
        }

        private boolean isActiveMessage(int messageId) {
            return activeMessageId != null && activeMessageId == messageId;
        }

        private static String createListId() {
            return UUID.randomUUID().toString();
        }
    }

    private static final class UserLists {
        private final List<ShoppingListItem> toBuy = new ArrayList<>();
        private final List<ShoppingListItem> bought = new ArrayList<>();

        private int addProducts(List<String> names) {
            int availableSlots = MAX_PRODUCTS_PER_LIST - toBuy.size() - bought.size();
            int productsToAdd = Math.max(0, Math.min(availableSlots, names.size()));
            names.stream()
                    .limit(productsToAdd)
                    .map(ShoppingListItem::create)
                    .forEach(toBuy::add);
            return productsToAdd;
        }

        private boolean moveToBought(String productId) {
            if (productId == null || productId.isBlank()) {
                return false;
            }

            Iterator<ShoppingListItem> iterator = toBuy.iterator();
            while (iterator.hasNext()) {
                ShoppingListItem item = iterator.next();
                if (item.id().equals(productId)) {
                    iterator.remove();
                    bought.add(item);
                    return true;
                }
            }
            return false;
        }

        private ShoppingListSnapshot snapshot() {
            List<ShoppingListItem> sortedToBuy = new ArrayList<>(toBuy);
            sortedToBuy.sort(Comparator.comparing(
                    ShoppingListItem::name,
                    String.CASE_INSENSITIVE_ORDER
            ));
            return new ShoppingListSnapshot(sortedToBuy, bought);
        }
    }
}
