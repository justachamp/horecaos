package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;

/**
 * Issued statements and the counts a statement is computed from (ADR 0088).
 *
 * <p>A statement row is written once with its lines and afterwards takes one
 * change, being voided; the database refuses anything else.
 */
@Repository
public class JdbcStatementStore {

    private final JdbcClient jdbc;

    public JdbcStatementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes an issued statement and its lines, and answers its number.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the month already has one
     */
    public String insertIssued(
            UUID id, Statement statement, String currency, String issuedBy, String reason, Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", statement.tenantId());
        params.put("periodKey", statement.periodKey());
        params.put("periodStart", utc(statement.periodStart()));
        params.put("periodEnd", utc(statement.periodEnd()));
        params.put("currency", currency);
        params.put("total", statement.totalMinor());
        params.put("subscriptionId", statement.subscriptionId());
        params.put("issuedBy", issuedBy);
        params.put("reason", reason);
        params.put("now", utc(now));
        String number = jdbc.sql("""
                        INSERT INTO commercial.statements (
                            id, tenant_id, number, period_key, period_start, period_end, currency,
                            total_minor, subscription_id, status, issued_by, issued_at, issue_reason)
                        VALUES (
                            :id, :tenantId,
                            'S-' || :periodKey || '-' || lpad(nextval('commercial.statement_number_seq')::text, 6, '0'),
                            :periodKey, :periodStart, :periodEnd, :currency,
                            :total, :subscriptionId, 'ISSUED', :issuedBy, :now, :reason)
                        RETURNING number
                        """).params(params).query(String.class).single();

        for (StatementLine line : statement.lines()) {
            jdbc.sql("""
                            INSERT INTO commercial.statement_lines (
                                tenant_id, statement_id, line_number, kind, reference_code, description,
                                quantity, unit_price_minor, amount_minor)
                            VALUES (:tenantId, :statementId, :lineNumber, :kind, :referenceCode, :description,
                                :quantity, :unitPrice, :amount)
                            """)
                    .param("tenantId", statement.tenantId())
                    .param("statementId", id)
                    .param("lineNumber", line.lineNumber())
                    .param("kind", line.kind())
                    .param("referenceCode", line.referenceCode())
                    .param("description", line.description())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPriceMinor())
                    .param("amount", line.amountMinor())
                    .update();
        }
        return number;
    }

    /** ISSUED to VOID; false when it was already void. */
    public boolean voidStatement(UUID tenantId, UUID id, String voidedBy, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE commercial.statements
                           SET status = 'VOID', voided_by = :voidedBy, voided_at = :now, void_reason = :reason
                         WHERE tenant_id = :tenantId AND id = :id AND status = 'ISSUED'
                        """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("voidedBy", voidedBy)
                        .param("reason", reason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Every statement the tenant has been issued, newest month first, without lines. */
    public List<Statement> list(UUID tenantId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId ORDER BY period_key DESC, issued_at DESC")
                .param("tenantId", tenantId)
                .query((row, number) -> header(row, List.of()))
                .list();
    }

    public Optional<Statement> find(UUID tenantId, UUID id) {
        List<StatementLine> lines = jdbc.sql("""
                        SELECT line_number, kind, reference_code, description, quantity, unit_price_minor,
                               amount_minor
                          FROM commercial.statement_lines
                         WHERE tenant_id = :tenantId AND statement_id = :id
                         ORDER BY line_number
                        """)
                .param("tenantId", tenantId)
                .param("id", id)
                .query((row, number) -> new StatementLine(
                        row.getInt("line_number"),
                        row.getString("kind"),
                        row.getString("reference_code"),
                        row.getString("description"),
                        row.getLong("quantity"),
                        row.getLong("unit_price_minor"),
                        row.getLong("amount_minor")))
                .list();
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query((row, number) -> header(row, lines))
                .optional();
    }

    /** The newest standing statement of each tenant in the list, for the arrears board. */
    public Map<UUID, Statement> latestIssued(List<UUID> tenantIds) {
        Map<UUID, Statement> latest = new HashMap<>();
        if (tenantIds.isEmpty()) {
            return latest;
        }
        jdbc.sql(SELECT + """
                         WHERE status = 'ISSUED' AND tenant_id IN (:tenantIds)
                         ORDER BY tenant_id, period_key DESC
                        """)
                .param("tenantIds", tenantIds)
                .query((row, number) -> header(row, List.of()))
                .list()
                .forEach(statement -> latest.putIfAbsent(statement.tenantId(), statement));
        return latest;
    }

    /**
     * A standing count as it stood at an instant: every movement and correction
     * recorded before it.
     *
     * <p>Brands and branches are standing counts in the usage ledger (ADR 0021),
     * so the number a statement bills per branch is the one the ledger says the
     * tenant had when the month closed, not the one it has on the day someone
     * issues the statement.
     */
    public long standingCountAt(UUID tenantId, String entitlementKey, Instant at) {
        Long count = jdbc.sql("""
                        SELECT
                            (SELECT COALESCE(SUM(quantity), 0) FROM commercial.usage_events
                              WHERE tenant_id = :tenantId AND entitlement_key = :key
                                AND period_key = 'LIFETIME' AND occurred_at < :at)
                          + (SELECT COALESCE(SUM(quantity_delta), 0) FROM commercial.usage_adjustments
                              WHERE tenant_id = :tenantId AND entitlement_key = :key
                                AND period_key = 'LIFETIME' AND created_at < :at)
                        """)
                .param("tenantId", tenantId)
                .param("key", entitlementKey)
                .param("at", utc(at))
                .query(Long.class)
                .single();
        return Math.max(0, count == null ? 0 : count);
    }

    /**
     * The metered periods of one key that belong to a month: the month itself,
     * and any billing period that started inside it.
     */
    public List<String> periodKeysWithin(UUID tenantId, String entitlementKey, String monthKey) {
        return jdbc.sql("""
                        SELECT period_key FROM commercial.usage_events
                         WHERE tenant_id = :tenantId AND entitlement_key = :key
                           AND (period_key = :month OR period_key LIKE :prefix)
                        UNION
                        SELECT period_key FROM commercial.usage_adjustments
                         WHERE tenant_id = :tenantId AND entitlement_key = :key
                           AND (period_key = :month OR period_key LIKE :prefix)
                        ORDER BY 1
                        """)
                .param("tenantId", tenantId)
                .param("key", entitlementKey)
                .param("month", monthKey)
                .param("prefix", monthKey + "-%")
                .query(String.class)
                .list();
    }

    private static final String SELECT = """
            SELECT id, tenant_id, number, period_key, period_start, period_end, currency, total_minor,
                   subscription_id, status, issued_by, issued_at, issue_reason, voided_by, voided_at,
                   void_reason
              FROM commercial.statements
            """;

    private static Statement header(ResultSet row, List<StatementLine> lines) throws SQLException {
        return new Statement(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("number"),
                row.getString("period_key"),
                Objects.requireNonNull(instant(row, "period_start"), "period_start is NOT NULL"),
                Objects.requireNonNull(instant(row, "period_end"), "period_end is NOT NULL"),
                row.getString("currency"),
                row.getLong("total_minor"),
                row.getObject("subscription_id", UUID.class),
                row.getString("status"),
                row.getString("issued_by"),
                instant(row, "issued_at"),
                row.getString("issue_reason"),
                row.getString("voided_by"),
                instant(row, "voided_at"),
                row.getString("void_reason"),
                lines);
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
