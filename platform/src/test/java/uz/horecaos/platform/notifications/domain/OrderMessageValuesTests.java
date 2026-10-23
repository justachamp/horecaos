package uz.horecaos.platform.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.notifications.domain.OrderMessageValues.Item;

class OrderMessageValuesTests {

    private static List<Item> two() {
        return List.of(new Item("Lavash", new BigDecimal("2.000"), 50_000L), new Item("Cola", BigDecimal.ONE, 8_000L));
    }

    @Test
    @DisplayName("the list names each item with its quantity, an ASCII x, and no price unless asked")
    void theListWithoutPrices() {
        assertThat(OrderMessageValues.productList(two(), "UZS", false)).isEqualTo("2x Lavash, 1x Cola");
    }

    @Test
    @DisplayName("with the switch on, each item is followed by its line total in the order's currency")
    void theListWithPrices() {
        assertThat(OrderMessageValues.productList(two(), "UZS", true))
                .isEqualTo("2x Lavash (50000 UZS), 1x Cola (8000 UZS)");
    }

    @Test
    @DisplayName("a half portion is 0.5 and never 0.500; a whole number never carries a trailing zero")
    void quantitiesAreReadable() {
        assertThat(OrderMessageValues.productList(
                        List.of(
                                new Item("Plov", new BigDecimal("0.500"), 20_000L),
                                new Item("Tea", new BigDecimal("10.000"), 1L)),
                        "UZS",
                        false))
                .isEqualTo("0.5x Plov, 10x Tea");
    }

    @Test
    @DisplayName("a long order names the first six items and counts the rest")
    void aLongListIsCut() {
        List<Item> many = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            many.add(new Item("Item" + i, BigDecimal.ONE, 1_000L));
        }
        assertThat(OrderMessageValues.productList(many, "UZS", false))
                .isEqualTo("1x Item1, 1x Item2, 1x Item3, 1x Item4, 1x Item5, 1x Item6, +3");
    }

    @Test
    @DisplayName("a message is never given an empty hole: no items, no promise and no time read as a dash")
    void absentFactsReadAsADash() {
        assertThat(OrderMessageValues.productList(List.of(), "UZS", true)).isEqualTo("—");
        assertThat(OrderMessageValues.deliveryTime(null, ZoneId.of("Asia/Tashkent")))
                .isEqualTo("—");
        assertThat(OrderMessageValues.preparationTime(null)).isEqualTo("—");
        assertThat(OrderMessageValues.preparationTime(35)).isEqualTo("35");
    }

    @Test
    @DisplayName("the promised time is on the branch's own clock, not UTC")
    void theTimeIsOnTheBranchesClock() {
        Instant promised = Instant.parse("2026-10-14T14:45:00Z");
        assertThat(OrderMessageValues.deliveryTime(promised, ZoneId.of("Asia/Tashkent")))
                .isEqualTo("19:45");
        assertThat(OrderMessageValues.deliveryTime(promised, ZoneId.of("UTC"))).isEqualTo("14:45");
    }

    @Test
    @DisplayName("a person with no name on file reads as a neutral word in their own language")
    void fallbacksAreWordsAndNotNames() {
        assertThat(OrderMessageValues.customerNameFallback("ru")).isEqualTo("клиент");
        assertThat(OrderMessageValues.customerNameFallback("uz-Latn")).isEqualTo("mijoz");
        assertThat(OrderMessageValues.customerNameFallback("en")).isEqualTo("customer");
        assertThat(OrderMessageValues.courierFirstNameFallback("ru")).isEqualTo("наш курьер");
        assertThat(OrderMessageValues.courierFirstNameFallback("uz-Latn")).isEqualTo("kuryerimiz");
        assertThat(OrderMessageValues.courierFirstNameFallback("kk")).isEqualTo("our courier");
    }
}
