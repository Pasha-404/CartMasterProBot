package org.cartmaster.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ShoppingListService {

    public static final int MAX_PRODUCT_NAME_LENGTH = 30;
    public static final int MAX_PRODUCTS_PER_LIST = 20;

    private final ConcurrentMap<Long, UserLists> listsByChat = new ConcurrentHashMap<>();

    public void reset(long chatId) {
        listsByChat.put(chatId, new UserLists());
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

        if (acceptedNames.isEmpty()) {
            return new AddProductsResult(0, rejectedTooLong, 0);
        }

        int rejectedTooLongCount = rejectedTooLong;
        AtomicReference<AddProductsResult> result = new AtomicReference<>();
        listsByChat.compute(chatId, (ignored, currentLists) -> {
            UserLists lists = currentLists == null ? new UserLists() : currentLists;
            int addedProducts = lists.addProducts(acceptedNames);
            result.set(new AddProductsResult(
                    addedProducts,
                    rejectedTooLongCount,
                    acceptedNames.size() - addedProducts
            ));
            return lists;
        });
        return result.get();
    }

    public boolean moveToBought(long chatId, String productId) {
        return moveToBought(chatId, productId, false);
    }

    public boolean moveToBoughtAndResetWhenCompleted(long chatId, String productId) {
        return moveToBought(chatId, productId, true);
    }

    private boolean moveToBought(long chatId, String productId, boolean resetWhenCompleted) {
        if (productId == null || productId.isBlank()) {
            return false;
        }

        AtomicBoolean moved = new AtomicBoolean(false);
        listsByChat.computeIfPresent(chatId, (ignored, lists) -> {
            moved.set(lists.moveToBought(productId));
            if (moved.get() && resetWhenCompleted && lists.hasNoProductsToBuy()) {
                return new UserLists();
            }
            return lists;
        });
        return moved.get();
    }

    public ShoppingListSnapshot getSnapshot(long chatId) {
        UserLists lists = listsByChat.get(chatId);
        return lists == null ? ShoppingListSnapshot.empty() : lists.snapshot();
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

    private static final class UserLists {
        private final List<ShoppingListItem> toBuy = new ArrayList<>();
        private final List<ShoppingListItem> bought = new ArrayList<>();

        private synchronized int addProducts(List<String> names) {
            int availableSlots = MAX_PRODUCTS_PER_LIST - toBuy.size() - bought.size();
            int productsToAdd = Math.max(0, Math.min(availableSlots, names.size()));
            names.stream()
                    .limit(productsToAdd)
                    .map(ShoppingListItem::create)
                    .forEach(toBuy::add);
            return productsToAdd;
        }

        private synchronized boolean moveToBought(String productId) {
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

        private synchronized boolean hasNoProductsToBuy() {
            return toBuy.isEmpty();
        }

        private synchronized ShoppingListSnapshot snapshot() {
            List<ShoppingListItem> sortedToBuy = new ArrayList<>(toBuy);
            sortedToBuy.sort(Comparator.comparing(
                    ShoppingListItem::name,
                    String.CASE_INSENSITIVE_ORDER
            ));
            return new ShoppingListSnapshot(sortedToBuy, bought);
        }
    }
}
