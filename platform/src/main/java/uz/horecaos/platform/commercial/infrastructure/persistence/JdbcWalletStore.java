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
import uz.horecaos.platform.commercial.domain.BonusGrantBalance;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;

/**
 * The wallet ledger and a tenant's payment method (ADR 0095).
 *
 * <p>{@code commercial.wallet_entries} is append-only at the database: this
 * store never issues an {@code UPDATE} or a {@code DELETE} against it, and
 * could not make either stick if it tried (V0211's trigger and its GRANT
 * both refuse). Every mutation this store performs is one {@code INSERT} of
 * a new entry.
 *
 * <p>{@code commercial.tenant_billing} is the one row every wallet mutation
 * takes {@code FOR UPDATE} on before it does anything else — see
 * {@link #lockBilling}. The ledger itself never needs a row lock: there is
 * no balance column on it to protect, only entries a SUM reads.
 */
@Repository
public class JdbcWalletStore {

    private final JdbcClient jdbc;

    public JdbcWalletStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The tenant's billing currency, the currency every wallet entry of theirs is written in. */
    public String currencyOf(UUID tenantId) {
        return jdbc.sql("SELECT default_currency FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .single();
    }

    /**
     * Ensures the tenant's billing row exists and locks it for the rest of
     * this transaction, serialising every wallet mutation for this tenant
     * onto one writer at a time.
     *
     * <p>Call this first, inside the transaction that goes on to insert a
     * wallet entry or change the payment method — never on its own.
     */
    public TenantBilling lockBilling(UUID tenantId, Instant now) {
        jdbc.sql("""
                        INSERT INTO commercial.tenant_billing (tenant_id, payment_method, updated_by, updated_at)
                        VALUES (:tenantId, 'INVOICE', 'system', :now)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """).param("tenantId", tenantId).param("now", utc(now)).update();
        return jdbc.sql("""
                        SELECT tenant_id, payment_method, card_token_reference, updated_by, updated_at
                          FROM commercial.tenant_billing
                         WHERE tenant_id = :tenantId
                         FOR UPDATE
                        """)
                .param("tenantId", tenantId)
                .query(JdbcWalletStore::billing)
                .single();
    }

    /** The tenant's payment method as it stands, with no lock and no default row written. */
    public Optional<TenantBilling> findBilling(UUID tenantId) {
        return jdbc.sql("""
                        SELECT tenant_id, payment_method, card_token_reference, updated_by, updated_at
                          FROM commercial.tenant_billing
                         WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query(JdbcWalletStore::billing)
                .optional();
    }

    /** Changes the payment method. Call only after {@link #lockBilling}, in the same transaction. */
    public void setPaymentMethod(
            UUID tenantId, PaymentMethod method, @Nullable String cardTokenReference, String updatedBy, Instant now) {
        jdbc.sql("""
                        UPDATE commercial.tenant_billing
                           SET payment_method = :method, card_token_reference = :cardTokenReference,
                               updated_by = :updatedBy, updated_at = :now
                         WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .param("method", method.name())
                .param("cardTokenReference", cardTokenReference)
                .param("updatedBy", updatedBy)
                .param("now", utc(now))
                .update();
    }

    /** Appends one wallet entry. The only write this store ever performs against the ledger itself. */
    public void append(WalletEntry entry) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", entry.id());
        params.put("tenantId", entry.tenantId());
        params.put("moneyKind", entry.moneyKind());
        params.put("entryType", entry.entryType());
        params.put("amount", entry.amountMinor());
        params.put("currency", entry.currency());
        params.put("statementId", entry.statementId());
        params.put("grantId", entry.grantId());
        params.put("expiresAt", utc(entry.expiresAt()));
        params.put("externalReference", entry.externalReference());
        params.put("reason", entry.reason());
        params.put("recordedBy", entry.recordedBy());
        params.put("approvedBy", entry.approvedBy());
        params.put("approvalRequestId", entry.approvalRequestId());
        params.put("now", utc(entry.createdAt()));

        jdbc.sql("""
                        INSERT INTO commercial.wallet_entries (
                            id, tenant_id, money_kind, entry_type, amount_minor, currency, statement_id,
                            grant_id, expires_at, external_reference, reason, recorded_by, approved_by,
                            approval_request_id, created_at)
                        VALUES (
                            :id, :tenantId, :moneyKind, :entryType, :amount, :currency, :statementId,
                            :grantId, :expiresAt, :externalReference, :reason, :recordedBy, :approvedBy,
                            :approvalRequestId, :now)
                        """).params(params).update();
    }

    /** The tenant's two balances: the SUM of its own entries, each money kind on its own. */
    public WalletBalances balances(UUID tenantId, String currency) {
        Map<String, Long> byKind = new HashMap<>();
        jdbc.sql("""
                        SELECT money_kind, SUM(amount_minor) AS total
                          FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId
                         GROUP BY money_kind
                        """)
                .param("tenantId", tenantId)
                .query((row, number) -> Map.entry(row.getString("money_kind"), row.getLong("total")))
                .list()
                .forEach(e -> byKind.put(e.getKey(), e.getValue()));
        return new WalletBalances(
                byKind.getOrDefault(WalletEntry.PAID, 0L), byKind.getOrDefault(WalletEntry.BONUS, 0L), currency);
    }

    /** The tenant's ledger, newest first, keyset-paginated on {@code created_at, id}. */
    public List<WalletEntry> ledger(UUID tenantId, @Nullable UUID afterId, int limit) {
        return jdbc.sql("""
                        SELECT id, tenant_id, money_kind, entry_type, amount_minor, currency, statement_id,
                               grant_id, expires_at, external_reference, reason, recorded_by, approved_by,
                               approval_request_id, created_at
                          FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId
                           AND (:afterId::uuid IS NULL OR (created_at, id) < (
                               SELECT created_at, id FROM commercial.wallet_entries
                                WHERE tenant_id = :tenantId AND id = :afterId))
                         ORDER BY created_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("afterId", afterId)
                .param("limit", limit)
                .query(JdbcWalletStore::entry)
                .list();
    }

    /** Live bonus grants with an unspent remainder, earliest expiry first — the order a statement draws from. */
    public List<BonusGrantBalance> liveGrants(UUID tenantId, Instant now) {
        return jdbc
                .sql("""
                        WITH grants AS (
                            SELECT id, amount_minor, currency, expires_at, reason
                              FROM commercial.wallet_entries
                             WHERE tenant_id = :tenantId AND entry_type = 'BONUS_GRANT'
                        )
                        SELECT g.id, g.amount_minor AS granted_minor, g.currency, g.expires_at, g.reason,
                               g.amount_minor + COALESCE((
                                   SELECT SUM(w.amount_minor) FROM commercial.wallet_entries w
                                    WHERE w.tenant_id = :tenantId AND w.grant_id = g.id), 0) AS remaining_minor
                          FROM grants g
                         WHERE g.expires_at > :now
                         ORDER BY g.expires_at ASC
                        """)
                .param("tenantId", tenantId)
                .param("now", utc(now))
                .query((row, number) -> new BonusGrantBalance(
                        row.getObject("id", UUID.class),
                        row.getLong("granted_minor"),
                        row.getLong("remaining_minor"),
                        row.getString("currency"),
                        Objects.requireNonNull(instant(row, "expires_at"), "a grant always has an expiry"),
                        row.getString("reason")))
                .list()
                .stream()
                .filter(grant -> grant.remainingMinor() > 0)
                .toList();
    }

    /** Every ISSUED statement's paid and due amounts, newest month first. */
    public List<StatementPayment> statementPayments(UUID tenantId) {
        return statementPayments(tenantId, false);
    }

    /** ISSUED statements still owing something, oldest first — the order later money pays them in. */
    public List<StatementPayment> openStatementsOldestFirst(UUID tenantId) {
        return statementPayments(tenantId, true);
    }

    private List<StatementPayment> statementPayments(UUID tenantId, boolean openOnly) {
        String sql = """
                SELECT s.id, s.number, s.period_key, s.total_minor,
                       COALESCE(-SUM(w.amount_minor), 0) AS paid_minor
                  FROM commercial.statements s
                  LEFT JOIN commercial.wallet_entries w
                    ON w.tenant_id = s.tenant_id AND w.statement_id = s.id AND w.entry_type = 'STATEMENT_PAYMENT'
                 WHERE s.tenant_id = :tenantId AND s.status = 'ISSUED'
                 GROUP BY s.id, s.number, s.period_key, s.total_minor
                """
                + (openOnly
                        ? " HAVING s.total_minor > COALESCE(-SUM(w.amount_minor), 0) ORDER BY s.period_key ASC"
                        : " ORDER BY s.period_key DESC");
        return jdbc.sql(sql)
                .param("tenantId", tenantId)
                .query((row, number) -> {
                    long total = row.getLong("total_minor");
                    long paid = row.getLong("paid_minor");
                    return new StatementPayment(
                            row.getObject("id", UUID.class),
                            row.getString("number"),
                            row.getString("period_key"),
                            total,
                            paid,
                            total - paid);
                })
                .list();
    }

    /**
     * Expired bonus grants that have never had a lapse entry written for them,
     * across every tenant, oldest expiry first.
     *
     * <p>A grant fully drawn down before it expired is skipped forever once its
     * remainder reaches zero and its own lapse is written elsewhere — see
     * {@link #grantRemaining} and {@code WalletService.expireGrantIfDue}, which
     * recomputes the remainder under the tenant's billing lock before deciding
     * whether there is anything left to lapse. A grant whose remainder was
     * already zero the moment it expired has no lapse entry to write (an entry
     * of amount zero is refused at the database) and is rescanned on every
     * pass; accepted rather than tracked separately, since the candidate set
     * stays small relative to the ledger.
     */
    public List<ExpiredGrantRef> expiredGrantCandidates(Instant now, int batchSize) {
        return jdbc.sql("""
                        SELECT tenant_id, id AS grant_id, currency
                          FROM commercial.wallet_entries g
                         WHERE entry_type = 'BONUS_GRANT'
                           AND expires_at <= :now
                           AND NOT EXISTS (
                               SELECT 1 FROM commercial.wallet_entries e
                                WHERE e.grant_id = g.id AND e.entry_type = 'BONUS_EXPIRY')
                         ORDER BY expires_at ASC
                         LIMIT :batchSize
                        """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .query((row, number) -> new ExpiredGrantRef(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("grant_id", UUID.class),
                        row.getString("currency")))
                .list();
    }

    /** One bonus grant's unspent remainder right now, whatever its expiry. */
    public long grantRemaining(UUID tenantId, UUID grantId) {
        Long total = jdbc.sql("""
                        SELECT amount_minor + COALESCE((
                                   SELECT SUM(amount_minor) FROM commercial.wallet_entries
                                    WHERE tenant_id = :tenantId AND grant_id = :grantId), 0)
                          FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId AND id = :grantId AND entry_type = 'BONUS_GRANT'
                        """)
                .param("tenantId", tenantId)
                .param("grantId", grantId)
                .query(Long.class)
                .single();
        return total == null ? 0 : total;
    }

    /** A candidate for the bonus expiry sweep: which grant, in which tenant, in what currency. */
    public record ExpiredGrantRef(UUID tenantId, UUID grantId, String currency) {}

    /** The tenant's total live PAID balance. */
    public long paidBalance(UUID tenantId) {
        Long total = jdbc.sql("""
                        SELECT SUM(amount_minor) FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId AND money_kind = 'PAID'
                        """).param("tenantId", tenantId).query(Long.class).single();
        return total == null ? 0 : total;
    }

    private static WalletEntry entry(ResultSet row, int number) throws SQLException {
        return new WalletEntry(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("money_kind"),
                row.getString("entry_type"),
                row.getLong("amount_minor"),
                row.getString("currency"),
                row.getObject("statement_id", UUID.class),
                row.getObject("grant_id", UUID.class),
                instant(row, "expires_at"),
                row.getString("external_reference"),
                row.getString("reason"),
                row.getString("recorded_by"),
                row.getString("approved_by"),
                row.getObject("approval_request_id", UUID.class),
                Objects.requireNonNull(instant(row, "created_at"), "created_at is NOT NULL"));
    }

    private static TenantBilling billing(ResultSet row, int number) throws SQLException {
        return new TenantBilling(
                row.getObject("tenant_id", UUID.class),
                PaymentMethod.valueOf(row.getString("payment_method")),
                row.getString("card_token_reference"),
                row.getString("updated_by"),
                Objects.requireNonNull(instant(row, "updated_at"), "updated_at is NOT NULL"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
