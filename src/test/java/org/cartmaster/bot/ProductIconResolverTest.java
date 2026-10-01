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
    void decoratesTheExpandedProductCatalog() {
        assertThat(resolver.decorate("Печенье овсяное")).isEqualTo("🍪 Печенье овсяное");
        assertThat(resolver.decorate("Арбуз")).isEqualTo("🍉 Арбуз");
        assertThat(resolver.decorate("Клубника свежая")).isEqualTo("🍓 Клубника свежая");
        assertThat(resolver.decorate("Ананас")).isEqualTo("🍍 Ананас");
        assertThat(resolver.decorate("Груши")).isEqualTo("🍐 Груши");
        assertThat(resolver.decorate("Черешня")).isEqualTo("🍒 Черешня");
        assertThat(resolver.decorate("Киви")).isEqualTo("🥝 Киви");
        assertThat(resolver.decorate("Дыня")).isEqualTo("🍈 Дыня");
        assertThat(resolver.decorate("Авокадо")).isEqualTo("🥑 Авокадо");
        assertThat(resolver.decorate("Баклажаны")).isEqualTo("🍆 Баклажаны");
        assertThat(resolver.decorate("Брокколи")).isEqualTo("🥦 Брокколи");
        assertThat(resolver.decorate("Кукуруза")).isEqualTo("🌽 Кукуруза");
        assertThat(resolver.decorate("Грибы")).isEqualTo("🍄 Грибы");
        assertThat(resolver.decorate("Оливки")).isEqualTo("🫒 Оливки");
        assertThat(resolver.decorate("Консервы")).isEqualTo("🥫 Консервы");
        assertThat(resolver.decorate("Мёд")).isEqualTo("🍯 Мёд");
        assertThat(resolver.decorate("Конфеты")).isEqualTo("🍬 Конфеты");
        assertThat(resolver.decorate("Пончики")).isEqualTo("🍩 Пончики");
        assertThat(resolver.decorate("Мороженое")).isEqualTo("🍦 Мороженое");
        assertThat(resolver.decorate("Кексы")).isEqualTo("🧁 Кексы");
        assertThat(resolver.decorate("Шампунь")).isEqualTo("🧴 Шампунь");
        assertThat(resolver.decorate("Губка для посуды")).isEqualTo("🧽 Губка для посуды");
        assertThat(resolver.decorate("Таблетки")).isEqualTo("💊 Таблетки");
    }

    @Test
    void givesCookiePriorityOverASecondaryChocolateIngredient() {
        assertThat(resolver.decorate("Печенье с шоколадом"))
                .isEqualTo("🍪 Печенье с шоколадом");
    }

    @Test
    void decoratesBeetNamesWithoutChangingUserText() {
        assertThat(resolver.decorate("Свёкла")).isEqualTo("🫜 Свёкла");
        assertThat(resolver.decorate("СВЕКЛА варёная")).isEqualTo("🫜 СВЕКЛА варёная");
        assertThat(resolver.decorate("Свекольный салат")).isEqualTo("🫜 Свекольный салат");
    }

    @Test
    void preservesUnknownProductNamesExactly() {
        assertThat(resolver.decorate("Неизвестный товар № 7")).isEqualTo("Неизвестный товар № 7");
        assertThat(resolver.decorate("Масло подсолнечное")).isEqualTo("Масло подсолнечное");
    }
}
