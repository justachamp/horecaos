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
 * The platform-curated branch-override-reason reference table (gap map row
 * {@code 1.3}, V0423) — {@link uz.horecaos.platform.ordering.infrastructure.persistence.JdbcRejectReasonStore}'s
 * own shape, restated for a different curated list. Read-only from the
 * application, matching V0423's own grant: the five reasons are seeded once,
 * by the migration, and read from here.
 */
@Repository
public class JdbcBranchOverrideReasonStore {

    private final JdbcClient jdbc;

    public JdbcBranchOverrideReasonStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every reason, active or not, in display order. */
    public List<ReasonRow> listAll() {
        return list(false);
    }

    /** The reasons an operator's override dialog may choose from, in display order. */
    public List<ReasonRow> listActive() {
        return list(true);
    }

    private List<ReasonRow> list(boolean activeOnly) {
        List<ReasonRow> reasons = jdbc.sql("""
                SELECT code, display_order, requires_note, active, created_at, updated_at
                FROM ordering.branch_override_reasons
                WHERE (:activeOnly = false OR active)
                ORDER BY display_order
                """)
                .param("activeOnly", activeOnly)
                .query(JdbcBranchOverrideReasonStore::mapReason)
                .list();

        Map<String, Map<String, String>> textsByCode = textsByCode();
        return reasons.stream()
                .map(reason -> reason.withLabels(textsByCode.getOrDefault(reason.code(), Map.of())))
                .toList();
    }

    public Optional<ReasonRow> find(String code) {
        Optional<ReasonRow> reason = jdbc.sql("""
                SELECT code, display_order, requires_note, active, created_at, updated_at
                FROM ordering.branch_override_reasons
                WHERE code = :code
                """)
                .param("code", code)
                .query(JdbcBranchOverrideReasonStore::mapReason)
                .optional();
        return reason.map(row -> row.withLabels(textsFor(code)));
    }

    private Map<String, String> textsFor(String code) {
        Map<String, String> byLocale = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT locale, label FROM ordering.branch_override_reason_texts
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
                SELECT reason_code, locale, label FROM ordering.branch_override_reason_texts
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

    /** @param labels the operator-facing label, per locale ({@code ru}, {@code uz-Latn}, {@code en}) */
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
