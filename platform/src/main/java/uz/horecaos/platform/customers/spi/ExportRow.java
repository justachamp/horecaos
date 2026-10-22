package uz.horecaos.platform.customers.spi;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Builds one row of a data-subject export (see {@link ExportTable}). Values are rendered once, here, so every
 * participant's bundle looks the same: instants as ISO-8601 UTC text, dates as ISO text, ids and enums as text.
 */
public final class ExportRow {

    private final Map<String, @Nullable Object> values = new LinkedHashMap<>();

    private ExportRow() {}

    public static ExportRow row() {
        return new ExportRow();
    }

    /** Adds a column. Null is kept as null: "we hold nothing here" is an answer, and a missing key is not. */
    public ExportRow put(String column, @Nullable Object value) {
        values.put(column, render(value));
        return this;
    }

    /** An amount in minor units beside its currency. */
    public ExportRow money(String column, long amountMinor, String currency) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amountMinor", amountMinor);
        money.put("currency", currency);
        values.put(column, money);
        return this;
    }

    /**
     * Renders a row a store read column by column ({@code JdbcClient...listOfRows()}), so a participant whose columns need
     * no decrypting does not map them one by one. Keys keep the order the statement selected them in.
     */
    public static Map<String, @Nullable Object> render(Map<String, ? extends @Nullable Object> raw) {
        ExportRow row = new ExportRow();
        raw.forEach((column, value) -> row.put(camelCase(column), value));
        return row.build();
    }

    /** A UUID column that cannot be null (a primary key, a foreign key), read from a raw row. */
    public static UUID id(Map<String, ?> raw, String column) {
        return (UUID) Objects.requireNonNull(raw.get(column), column);
    }

    /** A bigint column that cannot be null, read from a raw row. */
    public static long number(Map<String, ?> raw, String column) {
        return ((Number) Objects.requireNonNull(raw.get(column), column)).longValue();
    }

    /** A text column that cannot be null, read from a raw row. */
    public static String text(Map<String, ?> raw, String column) {
        return (String) Objects.requireNonNull(raw.get(column), column);
    }

    /** {@code product_name_snapshot} to {@code productNameSnapshot}: the bundle reads the same in every section. */
    public static String camelCase(String column) {
        StringBuilder camel = new StringBuilder(column.length());
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }

    public Map<String, @Nullable Object> build() {
        return values;
    }

    private static @Nullable Object render(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant.toString();
            case java.time.OffsetDateTime moment -> moment.toInstant().toString();
            case java.sql.Timestamp moment -> moment.toInstant().toString();
            case java.sql.Date day -> day.toLocalDate().toString();
            case LocalDate date -> date.toString();
            case UUID id -> id.toString();
            case Enum<?> constant -> constant.name();
            case String text -> text;
            case Number number -> number;
            case Boolean flag -> flag;
            case Map<?, ?> map -> map;
            case Iterable<?> list -> list;
            default -> value.toString();
        };
    }
}
