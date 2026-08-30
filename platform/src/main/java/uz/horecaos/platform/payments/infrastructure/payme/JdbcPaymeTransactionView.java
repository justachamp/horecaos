package uz.horecaos.platform.payments.infrastructure.payme;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.SomAmount;

/**
 * The read behind {@code CheckTransaction} and {@code GetStatement} (ADR 0013).
 *
 * <p>Kept in the Payme package rather than on the shared attempt store because the
 * shape it produces is Payme's: three timestamps derived from the append-only
 * transaction log, filtered on Payme's own creation time, scoped to one cashbox.
 * None of that means anything to Click.
 *
 * <p><strong>{@code GetStatement} returns every state.</strong> Payme's own Java
 * template filters to completed transactions, which silently deletes cancelled and
 * pending transactions from the reconciliation that is the only reason the method
 * exists. There is no status predicate in the query below, and there must never be
 * one. What <em>is</em> excluded is an attempt that never became a Payme
 * transaction at all — the docs are explicit that a {@code CreateTransaction} which
 * ended in an error must not appear — and the test for that is a null
 * {@code external_payment_id}, since Payme's id is written only when the create
 * succeeded.
 *
 * <p>Both bounds are inclusive, ordered ascending, exactly as the docs state.
 */
@Repository
public class JdbcPaymeTransactionView {

    /**
     * The three merchant-side clocks, each read from the append-only log rather
     * than from a column on the attempt.
     *
     * <p>They have to be stable across a replay: Payme sends every mutating method
     * at least twice and requires the second response to match the first, so
     * {@code perform_time} must be the moment of the first capture and not the
     * moment of the second call. A timestamp derived from the transaction row is
     * that by construction; one taken from the clock at answer time is not.
     */
    private static final String SELECT = """
            SELECT a.id, a.tenant_id, a.external_payment_id, a.merchant_trans_id,
                   a.provider_created_at, a.requested_amount_minor, a.currency,
                   a.status, a.provider_reason,
                   (SELECT min(t.occurred_at) FROM payments.payment_transactions t
                     WHERE t.tenant_id = a.tenant_id AND t.attempt_id = a.id
                       AND t.transaction_type = 'RESERVE') AS create_time,
                   (SELECT min(t.occurred_at) FROM payments.payment_transactions t
                     WHERE t.tenant_id = a.tenant_id AND t.attempt_id = a.id
                       AND t.transaction_type = 'CAPTURE') AS perform_time,
                   (SELECT min(t.occurred_at) FROM payments.payment_transactions t
                     WHERE t.tenant_id = a.tenant_id AND t.attempt_id = a.id
                       AND t.transaction_type IN ('CANCEL', 'EXPIRE', 'REVERSE', 'REFUND'))
                     AS cancel_time
            FROM payments.payment_attempts a
            WHERE a.tenant_id = :tenantId
              AND a.merchant_binding_id = :bindingId
              AND a.provider_type = 'PAYME'
              AND a.external_payment_id IS NOT NULL
            """;

    private final JdbcClient jdbc;

    public JdbcPaymeTransactionView(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One transaction, by the id Payme minted.
     *
     * <p>Scoped to the binding as well as the tenant. An endpoint belongs to one
     * cashbox, so a transaction id belonging to another cashbox must read as "not
     * found" rather than be answered across the account boundary.
     */
    public Optional<PaymeTransactionView> find(UUID tenantId, UUID bindingId, String paymeTransactionId) {
        return jdbc.sql(SELECT + " AND a.external_payment_id = :paymeTransactionId")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("paymeTransactionId", paymeTransactionId)
                .query(JdbcPaymeTransactionView::map)
                .optional();
    }

    /**
     * Every transaction Payme created in the window, whatever became of it.
     *
     * <p>Filtered on {@code provider_created_at} — the {@code params.time} Payme
     * handed over — because the docs require the search to run on the creation date
     * <em>in the Payme system</em> and not on the merchant's own. The two differ by
     * however long the create took, which is enough to move a transaction across a
     * day boundary in a reconciliation.
     */
    public List<PaymeTransactionView> between(UUID tenantId, UUID bindingId, Instant from, Instant to) {
        return jdbc.sql(SELECT + """
                  AND a.provider_created_at >= :from
                  AND a.provider_created_at <= :to
                ORDER BY a.provider_created_at
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query(JdbcPaymeTransactionView::map)
                .list();
    }

    private static PaymeTransactionView map(ResultSet row, int rowNumber) throws SQLException {
        return new PaymeTransactionView(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("external_payment_id"),
                row.getString("merchant_trans_id"),
                instant(row, "provider_created_at"),
                new SomAmount(row.getLong("requested_amount_minor"), row.getString("currency")),
                PaymentAttemptStatus.valueOf(row.getString("status")),
                row.getString("provider_reason"),
                instant(row, "create_time"),
                instant(row, "perform_time"),
                instant(row, "cancel_time"));
    }

    /**
     * Read as an {@code OffsetDateTime} and converted, rather than through
     * {@code getTimestamp}, which applies the JVM's default zone. On a module whose
     * timestamps decide whether a twelve-hour window has closed, that difference is
     * a performed transaction that should have been cancelled.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
