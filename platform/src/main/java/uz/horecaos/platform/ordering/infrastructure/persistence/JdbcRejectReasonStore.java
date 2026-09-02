package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The platform-curated reject-reason reference table (wave 24, V0119).
 *
 * <p>Read-only from the application, matching the migration's own grant: this
 * store never inserts, updates or archives a row, because nothing today
 * authors one — the eight reasons are seeded once, by the migration, and read
 * from here. See V0119's own header for why this is not a
 * {@link JdbcOutcomeReasonStore}: that store's rows are authored per tenant
 * and carry consequence fields a reject reason never varies by.
 */
@Repository
public class JdbcRejectReasonStore {

    private final JdbcClient jdbc;

    public JdbcRejectReasonStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every reason, active or not, in display order — what the authoring-free "manage" read needs. */
    public List<ReasonRow> listAll() {
        return list(false);
    }

    /** The reasons an operator (or the bot's picker) may choose from, in display order. */
    public List<ReasonRow> listActive() {
        return list(true);
    }

    private List<ReasonRow> list(boolean activeOnly) {
        List<ReasonRow> reasons = jdbc.sql("""
                SELECT code, display_order, requires_note, active, created_at, updated_at
                FROM ordering.order_reject_reasons
                WHERE (:activeOnly = false OR active)
                ORDER BY display_order
                """)
                .param("activeOnly", activeOnly)
                .query(JdbcRejectReasonStore::mapReason)
                .list();

        Map<String, Map<String, String>> textsByCode = textsByCode();
        return reasons.stream()
                .map(reason -> reason.withLabels(textsByCode.getOrDefault(reason.code(), Map.of())))
                .toList();
    }

    public Optional<ReasonRow> find(String code) {
        Optional<ReasonRow> reason = jdbc.sql("""
                SELECT code, display_order, requires_note, active, created_at, updated_at
                FROM ordering.order_reject_reasons
                WHERE code = :code
                """)
                .param("code", code)
                .query(JdbcRejectReasonStore::mapReason)
                .optional();
        return reason.map(row -> row.withLabels(textsFor(code)));
    }

    private Map<String, String> textsFor(String code) {
        Map<String, String> byLocale = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT locale, label FROM ordering.order_reject_reason_texts
                WHERE reason_code = :code ORDER BY locale
                """)
                .param("code", code)
                .query((row, number) -> Map.entry(row.getString("locale"), row.getString("label")))
                .list()
                .forEach(entry -> byLocale.put(entry.getKey(), entry.getValue()));
        return byLocale;
    }

    /** Every reason's labels in one query, keyed by reason code — what {@link #list} builds its rows from. */
    private Map<String, Map<String, String>> textsByCode() {
        Map<String, Map<String, String>> byCode = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT reason_code, locale, label FROM ordering.order_reject_reason_texts
                ORDER BY reason_code, locale
                """)
                .query((row, number) ->
                        new Object[] {row.getString("reason_code"), row.getString("locale"), row.getString("label")})
                .list()
                .forEach(triple -> byCode.computeIfAbsent((String) triple[0], key -> new LinkedHashMap<>())
                        .put((String) triple[1], (String) triple[2]));
        return byCode;
    }

    private static ReasonRow mapReason(ResultSet row, int number) throws SQLException {
        return new ReasonRow(
                row.getString("code"),
                row.getInt("display_order"),
                row.getBoolean("requires_note"),
                row.getBoolean("active"),
                Map.of(),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /** @param labels the customer/operator-facing label, per locale ({@code ru}, {@code uz-Latn}, {@code en}) */
    public record ReasonRow(
            String code,
            int displayOrder,
            boolean requiresNote,
            boolean active,
            Map<String, String> labels,
            Instant createdAt,
            Instant updatedAt) {

        ReasonRow withLabels(Map<String, String> labels) {
            return new ReasonRow(code, displayOrder, requiresNote, active, labels, createdAt, updatedAt);
        }
    }
}
