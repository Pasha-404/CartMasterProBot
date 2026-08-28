package org.cartmaster.bot;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProductIconResolver {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private static final List<IconRule> ICON_RULES = List.of(
            new IconRule("🧃", List.of("сок", "нектар", "морс")),
            new IconRule("🍫", List.of("шоколад")),
            new IconRule("🧈", List.of("сливочн")),
            new IconRule("🥛", List.of("молок", "кефир", "йогурт", "ряженк")),
            new IconRule("🧀", List.of("сыр", "творог")),
            new IconRule("🥚", List.of("яйц")),
            new IconRule("🍞", List.of("хлеб", "батон", "булк")),
            new IconRule("🍝", List.of("макарон", "спагет", "лапш")),
            new IconRule("🍚", List.of("рис")),
            new IconRule("🌾", List.of("гречк", "круп")),
            new IconRule("🧂", List.of("соль")),
            new IconRule("☕", List.of("кофе")),
            new IconRule("🍵", List.of("чай")),
            new IconRule("💧", List.of("вода", "минералк")),
            new IconRule("🥩", List.of("мяс", "говядин", "свинин")),
            new IconRule("🍗", List.of("куриц", "индейк")),
            new IconRule("🌭", List.of("колбас", "сосиск")),
            new IconRule("🐟", List.of("рыб")),
            new IconRule("🍤", List.of("кревет", "морепродукт")),
            new IconRule("🍎", List.of("яблок")),
            new IconRule("🍌", List.of("банан")),
            new IconRule("🍊", List.of("апельсин", "мандарин")),
            new IconRule("🍋", List.of("лимон")),
            new IconRule("🍇", List.of("виноград")),
            new IconRule("🥔", List.of("картошк", "картофел")),
            new IconRule("🥕", List.of("морков")),
            new IconRule("🧅", List.of("лук")),
            new IconRule("🧄", List.of("чеснок")),
            new IconRule("🍅", List.of("помидор", "томат")),
            new IconRule("🥒", List.of("огурц")),
            new IconRule("🥬", List.of("капуст")),
            new IconRule("🧻", List.of("бумаг")),
            new IconRule("🧼", List.of("мыло")),
            new IconRule("🪥", List.of("зубн"))
    );

    public String decorate(String productName) {
        if (productName == null || productName.isBlank()) {
            return productName;
        }

        List<String> words = extractWords(productName);
        for (IconRule rule : ICON_RULES) {
            if (rule.matches(words)) {
                return rule.icon() + " " + productName;
            }
        }
        return productName;
    }

    private List<String> extractWords(String productName) {
        Matcher matcher = WORD_PATTERN.matcher(
                productName.toLowerCase(Locale.ROOT).replace('ё', 'е')
        );
        java.util.ArrayList<String> words = new java.util.ArrayList<>();
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private record IconRule(String icon, List<String> wordPrefixes) {
        private boolean matches(List<String> words) {
            return words.stream().anyMatch(word -> wordPrefixes.stream().anyMatch(word::startsWith));
        }
    }
}
