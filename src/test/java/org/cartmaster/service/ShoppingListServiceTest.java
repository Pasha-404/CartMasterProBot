package org.cartmaster.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingListServiceTest {

    private final ShoppingListService service = new ShoppingListService();

    @Test
    void addsProductsSeparatedByCommasAndNewLines() {
        ShoppingListService.AddProductsResult result = service.addProducts(
                1L,
                "Молоко, хлеб\nЯблоки\r\n  сыр  "
        );

        assertThat(result.addedProducts()).isEqualTo(4);
        assertThat(service.getSnapshot(1L).toBuy())
                .extracting(ShoppingListService.ShoppingListItem::name)
                .containsExactlyInAnyOrder("Молоко", "хлеб", "Яблоки", "сыр");
    }

    @Test
    void keepsDecimalCommasInProductNames() {
        service.addProducts(1L, "Молоко 3,2%, хлеб");

        assertThat(service.getSnapshot(1L).toBuy())
                .extracting(ShoppingListService.ShoppingListItem::name)
                .containsExactlyInAnyOrder("Молоко 3,2%", "хлеб");
    }

    @Test
    void usesUniqueIdsAndMakesRepeatedCallbackIdempotent() {
        service.addProducts(1L, "Молоко, Молоко");
        List<ShoppingListService.ShoppingListItem> items = service.getSnapshot(1L).toBuy();

        assertThat(items).extracting(ShoppingListService.ShoppingListItem::id).doesNotHaveDuplicates();

        String firstItemId = items.get(0).id();
        assertThat(service.moveToBought(1L, firstItemId)).isTrue();
        assertThat(service.moveToBought(1L, firstItemId)).isFalse();
        assertThat(service.getSnapshot(1L).toBuy()).hasSize(1);
        assertThat(service.getSnapshot(1L).bought()).hasSize(1);
    }

    @Test
    void resetClearsBothLists() {
        service.addProducts(1L, "Хлеб, Молоко");
        String itemId = service.getSnapshot(1L).toBuy().get(0).id();
        service.moveToBought(1L, itemId);

        service.reset(1L);

        assertThat(service.getSnapshot(1L).toBuy()).isEmpty();
        assertThat(service.getSnapshot(1L).bought()).isEmpty();
    }

    @Test
    void startsNewListWhenTheLastProductIsBought() {
        service.addProducts(1L, "Молоко, Хлеб");
        String firstProductId = service.getSnapshot(1L).toBuy().get(0).id();

        assertThat(service.moveToBoughtAndResetWhenCompleted(1L, firstProductId)).isTrue();
        assertThat(service.getSnapshot(1L).toBuy()).hasSize(1);
        assertThat(service.getSnapshot(1L).bought()).hasSize(1);

        String lastProductId = service.getSnapshot(1L).toBuy().get(0).id();
        assertThat(service.moveToBoughtAndResetWhenCompleted(1L, lastProductId)).isTrue();

        assertThat(service.getSnapshot(1L).toBuy()).isEmpty();
        assertThat(service.getSnapshot(1L).bought()).isEmpty();
    }

    @Test
    void keepsAllProductsAddedConcurrently() throws Exception {
        int productCount = ShoppingListService.MAX_PRODUCTS_PER_LIST;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ShoppingListService.AddProductsResult>> tasks = IntStream.range(0, productCount)
                    .mapToObj(index -> (Callable<ShoppingListService.AddProductsResult>) () ->
                            service.addProducts(1L, "Товар " + index)
                    )
                    .toList();

            List<Future<ShoppingListService.AddProductsResult>> results = executor.invokeAll(tasks);
            for (Future<ShoppingListService.AddProductsResult> result : results) {
                assertThat(result.get().addedProducts()).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(service.getSnapshot(1L).toBuy()).hasSize(productCount);
    }

    @Test
    void rejectsNamesThatExceedTheConfiguredLimit() {
        String productName = "а".repeat(ShoppingListService.MAX_PRODUCT_NAME_LENGTH + 1);

        ShoppingListService.AddProductsResult result = service.addProducts(1L, productName);

        assertThat(result.addedProducts()).isZero();
        assertThat(result.rejectedTooLong()).isEqualTo(1);
        assertThat(service.getSnapshot(1L).toBuy()).isEmpty();
    }

    @Test
    void rejectsProductsBeyondTheListLimit() {
        String products = IntStream.range(0, ShoppingListService.MAX_PRODUCTS_PER_LIST + 1)
                .mapToObj(index -> "Товар" + index)
                .collect(java.util.stream.Collectors.joining(","));

        ShoppingListService.AddProductsResult result = service.addProducts(1L, products);

        assertThat(result.addedProducts()).isEqualTo(ShoppingListService.MAX_PRODUCTS_PER_LIST);
        assertThat(result.rejectedByListLimit()).isEqualTo(1);
        assertThat(service.getSnapshot(1L).toBuy()).hasSize(ShoppingListService.MAX_PRODUCTS_PER_LIST);
    }
}
