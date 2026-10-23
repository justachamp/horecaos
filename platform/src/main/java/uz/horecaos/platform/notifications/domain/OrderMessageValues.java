package uz.horecaos.platform.notifications.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How the facts of an order become the words of a message (ADR 0184, row 10.9a).
 *
 * <p>Pure text, no I/O: the facts arrive as arguments and the sentence leaves as a return value,
 * so every rule below is a unit test and none of them needs a database. What reads the order and
 * decides which values a template asked for is {@code NotificationEligibilityService}; what this class
 * decides is only the shape of each value.
 *
 * <p><strong>Two groups, and the line between them is D21.</strong> The product list, the times, the
 * restaurant and the money are facts about the order and are frozen onto the notification row with the
 * rest of its variables. The customer's name and the courier's first name are about a person: they have
 * no builder here. This class holds only their <em>fallback</em>, the neutral word a message reads with
 * when the person has no name on file, because a fallback is a word and not a person.
 *
 * <p>A fact that does not exist for this order reads as {@link #ABSENT} or as zero, never as an
 * empty string and never as a thrown exception: a message with a hole in it is a worse message than a
 * dash, and a message that throws at 22:00 is a message the customer never gets.
 */
public final class OrderMessageValues {

    /** What a time that was never promised reads as. */
    public static final String ABSENT = "—";

    /**
     * How many items a product list names before it counts the rest. An SMS segment is 70 or 160
     * characters, and a list that names forty items is a list nobody reads.
     */
    static final int MAX_ITEMS = 6;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private OrderMessageValues() {}

    /** One line of an order, as a message needs it: a name, how many, and what the line came to. */
    public record Item(String name, BigDecimal quantity, long lineTotalMinor) {}

    /**
     * The order's items on one line, e.g. {@code 2x Lavash, 1x Cola}.
     *
     * <p>With {@code showPrices}, each item is followed by the line's total in the order's currency,
     * e.g. {@code 2x Lavash (50000 UZS)}: Delever's «show item prices» switch, which a template version
     * carries (V0677). An ASCII {@code x} rather than the multiplication sign, because the sign is
     * outside GSM-7 and moves a whole Uzbek or English SMS into UCS-2, where a segment is 70 characters
     * and not 160. More than {@link #MAX_ITEMS} items name the first few and count the rest, {@code +3}.
     */
    public static String productList(List<Item> items, String currency, boolean showPrices) {
        if (items.isEmpty()) {
            return ABSENT;
        }
        StringBuilder list = new StringBuilder();
        int named = Math.min(items.size(), MAX_ITEMS);
        for (int index = 0; index < named; index++) {
            Item item = items.get(index);
            if (index > 0) {
                list.append(", ");
            }
            list.append(quantity(item.quantity()))
                    .append("x ")
                    .append(item.name().strip());
            if (showPrices) {
                list.append(" (")
                        .append(MoneyText.format(item.lineTotalMinor(), currency))
                        .append(currency == null || currency.isBlank() ? "" : " " + currency)
                        .append(')');
            }
        }
        if (items.size() > named) {
            list.append(", +").append(items.size() - named);
        }
        return list.toString();
    }

    /** {@code 2} for two, {@code 0.5} for a half portion: never {@code 2.000}. */
    static String quantity(BigDecimal quantity) {
        BigDecimal stripped = quantity.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** The promised time on the branch's own clock, {@code 19:45}, or {@link #ABSENT} when no promise was made. */
    public static String deliveryTime(@Nullable Instant promisedAt, ZoneId zone) {
        return promisedAt == null ? ABSENT : CLOCK.format(promisedAt.atZone(zone));
    }

    /** The promised preparation time in whole minutes, or {@link #ABSENT} when none was promised. */
    public static String preparationTime(@Nullable Integer minutes) {
        return minutes == null ? ABSENT : Integer.toString(minutes);
    }

    /**
     * What {@code customerName} reads as for a customer with no name on file. A neutral word in the
     * customer's language, so «Hello, customer» and not «Hello, {{customerName}}» and not «Hello, ».
     */
    public static String customerNameFallback(String localeTag) {
        return switch (localeTag) {
            case "ru" -> "клиент";
            case "uz-Latn" -> "mijoz";
            default -> "customer";
        };
    }

    /** What {@code courierFirstName} reads as when no in-house courier, or no name, is on record. */
    public static String courierFirstNameFallback(String localeTag) {
        return switch (localeTag) {
            case "ru" -> "наш курьер";
            case "uz-Latn" -> "kuryerimiz";
            default -> "our courier";
        };
    }
}
