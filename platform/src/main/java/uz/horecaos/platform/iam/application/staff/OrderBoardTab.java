package uz.horecaos.platform.iam.application.staff;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The order board's seven tabs (orders.md §2.3), the tab a saved view opens on (ADR 0160). Spelled as the
 * console spells them in {@code /orders?tab=}.
 */
public enum OrderBoardTab {
    ATTENTION("attention"),
    NEW("new"),
    PREPARING("preparing"),
    DELIVERING("delivering"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    ALL("all");

    private static final Map<String, OrderBoardTab> BY_ID =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(OrderBoardTab::id, Function.identity()));

    private final String id;

    OrderBoardTab(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public static Optional<OrderBoardTab> ofId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
