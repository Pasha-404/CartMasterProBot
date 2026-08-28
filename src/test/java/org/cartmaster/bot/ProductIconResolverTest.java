package org.cartmaster.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductIconResolverTest {

    private final ProductIconResolver resolver = new ProductIconResolver();

    @Test
    void decoratesKnownProductsUsingWordForms() {
        assertThat(resolver.decorate("МОЛОКО 3,2%")).isEqualTo("🥛 МОЛОКО 3,2%");
        assertThat(resolver.decorate("Хлеб белый")).isEqualTo("🍞 Хлеб белый");
        assertThat(resolver.decorate("помидоры черри")).isEqualTo("🍅 помидоры черри");
    }

    @Test
    void preservesUnknownProductNamesExactly() {
        assertThat(resolver.decorate("Неизвестный товар № 7")).isEqualTo("Неизвестный товар № 7");
    }
}
