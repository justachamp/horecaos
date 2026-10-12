package uz.horecaos.platform.iam.application.staff;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The order board's column vocabulary, the one list a stored document is validated against (ADR 0160,
 * Decision 3) and the one the generated TypeScript union is generated from.
 *
 * <p>A document stores a <em>change from the default</em>: {@code columns.hide} lists columns that are
 * shown by default and the person turned off, {@code columns.show} lists columns that are off by default
 * and the person turned on. Stored that way, a column a later release adds to the default set is not
 * hidden by an old choice, and one a later release adds to the addable set is not shown by one.
 *
 * <p>Five columns are always shown and cannot be hidden ({@link #ALWAYS_SHOWN}); they are not part of
 * this vocabulary because no document can name them, which is also how the validator tells «that column
 * cannot be hidden» from «there is no such column».
 *
 * <p>{@code courierEta} and {@code source} are deliberately absent: the board read carries neither of
 * their fields yet, and each joins by a {@code document_schema} bump when it does (ADR 0160, «Board
 * columns»). A column is offered only when the read gives its cell something to draw.
 */
public enum OrderBoardColumn {
    BRANCH("branch", true),
    CHANNEL("channel", true),
    CUSTOMER("customer", true),
    ITEMS("items", true),
    PAYMENT("payment", true),
    COURIER("courier", true),
    CREATED_BY("createdBy", false),
    ACCEPTED_BY("acceptedBy", false),
    COURIER_TYPE("courierType", false),
    DISCOUNT("discount", false);

    /** The columns of the table that are never hideable: not part of the vocabulary, only named in refusals. */
    public static final Set<String> ALWAYS_SHOWN = Set.of("number", "time", "total", "status", "actions");

    private static final java.util.Map<String, OrderBoardColumn> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(OrderBoardColumn::key, Function.identity()));

    private final String key;
    private final boolean shownByDefault;

    OrderBoardColumn(String key, boolean shownByDefault) {
        this.key = key;
        this.shownByDefault = shownByDefault;
    }

    /** The wire name: lower camel, as the console's registry spells it. */
    @JsonValue
    public String key() {
        return key;
    }

    /** True for a column the board draws before anybody chooses (hideable); false for one that is addable. */
    public boolean shownByDefault() {
        return shownByDefault;
    }

    public static Optional<OrderBoardColumn> ofKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
