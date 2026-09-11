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
        params.put("subscriptionId", entry.subscriptionId());
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
                            grant_id, subscription_id, expires_at, external_reference, reason, recorded_by,
                            approved_by, approval_request_id, created_at)
                        VALUES (
                            :id, :tenantId, :moneyKind, :entryType, :amount, :currency, :statementId,
                            :grantId, :subscriptionId, :expiresAt, :externalReference, :reason, :recordedBy,
                            :approvedBy, :approvalRequestId, :now)
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
                               grant_id, subscription_id, expires_at, external_reference, reason, recorded_by,
                               approved_by, approval_request_id, created_at
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

    /**
     * One of the tenant's own entries of a given type, by id.
     *
     * <p>Constrained on the tenant as well as the id, so an entry id typed
     * against the wrong tenant is not-found rather than readable, and on the
     * type so a caller that means "the deposit this reversal takes back"
     * cannot be handed a top-up or a statement payment instead.
     */
    public Optional<WalletEntry> findEntryOfType(UUID tenantId, UUID entryId, String entryType) {
        return jdbc.sql("""
                        SELECT id, tenant_id, money_kind, entry_type, amount_minor, currency, statement_id,
                               grant_id, subscription_id, expires_at, external_reference, reason, recorded_by,
                               approved_by, approval_request_id, created_at
                          FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId AND id = :entryId AND entry_type = :entryType
                        """)
                .param("tenantId", tenantId)
                .param("entryId", entryId)
                .param("entryType", entryType)
                .query(JdbcWalletStore::entry)
                .optional();
    }

    /**
     * The tenant's entry of one of {@code entryTypes} already holding this
     * normalised reference, if there is one.
     *
     * <p>Read before money in is appended, under the tenant's billing lock that
     * already serialises every writer of this tenant's wallet, so the answer is
     * authoritative rather than advisory. It exists so the refusal can say which
     * of two very different things happened: the recorder re-typed a reference
     * that is on file, or a genuinely different wire normalises onto one that
     * is. The second used to be reported as the first, sending the recorder to
     * search the ledger for a string that is not in it.
     *
     * <p>The unique indexes remain the stop that actually holds: this query is a
     * better message, not a substitute for a constraint.
     */
    public Optional<WalletEntry> findMoneyInByNormalisedReference(
            UUID tenantId, String normalisedReference, List<String> entryTypes) {
        return jdbc.sql("""
                        SELECT id, tenant_id, money_kind, entry_type, amount_minor, currency, statement_id,
                               grant_id, subscription_id, expires_at, external_reference, reason, recorded_by,
                               approved_by, approval_request_id, created_at
                          FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId
                           AND entry_type IN (:entryTypes)
                           AND external_reference_normalised = :reference
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("entryTypes", entryTypes)
                .param("reference", normalisedReference)
                .query(JdbcWalletStore::entry)
                .optional();
    }

    /** Every ISSUED statement's paid and due amounts, newest month first. */
    public List<StatementPayment> statementPayments(UUID tenantId) {
        return statementPayments(tenantId, null);
    }

    /**
     * ISSUED statements still owing something, in {@code currency}, oldest
     * first — the order later money pays them in.
     *
     * <p>The currency is the wallet's, and a statement in another one is not a
     * candidate: a wallet holds one currency (the tenant's billing currency),
     * so there is no rate at which its money could settle a statement priced
     * in another. A plan version sold in a second currency therefore leaves
     * its statements to be collected by invoice rather than paid at an
     * invented rate.
     */
    public List<StatementPayment> openStatementsOldestFirst(UUID tenantId, String currency) {
        return statementPayments(tenantId, currency);
    }

    private List<StatementPayment> statementPayments(UUID tenantId, @Nullable String openInCurrency) {
        String sql = """
                SELECT s.id, s.number, s.period_key, s.currency, s.total_minor,
                       COALESCE(-SUM(w.amount_minor), 0) AS paid_minor
                  FROM commercial.statements s
                  LEFT JOIN commercial.wallet_entries w
                    ON w.tenant_id = s.tenant_id AND w.statement_id = s.id
                   AND w.entry_type IN ('STATEMENT_PAYMENT', 'STATEMENT_REVERSAL')
                 WHERE s.tenant_id = :tenantId AND s.status = 'ISSUED'
                """
                + (openInCurrency == null ? "" : " AND s.currency = :currency")
                + """
                 GROUP BY s.id, s.number, s.period_key, s.currency, s.total_minor
                """
                + (openInCurrency == null
                        ? " ORDER BY s.period_key DESC"
                        : " HAVING s.total_minor > COALESCE(-SUM(w.amount_minor), 0) ORDER BY s.period_key ASC");
        var query = jdbc.sql(sql).param("tenantId", tenantId);
        if (openInCurrency != null) {
            query = query.param("currency", openInCurrency);
        }
        return query.query((row, number) -> {
                    long total = row.getLong("total_minor");
                    long paid = row.getLong("paid_minor");
                    return new StatementPayment(
                            row.getObject("id", UUID.class),
                            row.getString("number"),
                            row.getString("period_key"),
                            row.getString("currency"),
                            total,
                            paid,
                            total - paid);
                })
                .list();
    }

    /**
     * Expired bonus grants with something left to lapse, across every tenant,
     * oldest expiry first.
     *
     * <p>The condition is the remainder, not "has no lapse entry yet". Those
     * two look interchangeable and are not: a grant already lapsed to zero is
     * excluded by either, but a grant whose remainder came back — a statement
     * it had paid was voided after it expired, so the draw was given back to
     * it — is a candidate again under this condition and would be invisible
     * forever under the other, leaving bonus money that can neither be spent
     * (a statement draws on live grants only) nor lapse. It also keeps a
     * fully spent grant from sitting in every batch it can only be skipped in.
     * The remainder is recomputed under the tenant's billing lock before
     * anything is written — see {@code WalletService.expireGrantIfDue} — so
     * this query decides what to look at, never what to write.
     */
    public List<ExpiredGrantRef> expiredGrantCandidates(Instant now, int batchSize) {
        return jdbc.sql("""
                        SELECT tenant_id, id AS grant_id, currency
                          FROM commercial.wallet_entries g
                         WHERE entry_type = 'BONUS_GRANT'
                           AND expires_at <= :now
                           AND g.amount_minor + COALESCE((
                                   SELECT SUM(d.amount_minor) FROM commercial.wallet_entries d
                                    WHERE d.tenant_id = g.tenant_id AND d.grant_id = g.id), 0) > 0
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

    /** One bonus grant's unspent remainder right now, whatever its expiry; zero when there is no such grant. */
    public long grantRemaining(UUID tenantId, UUID grantId) {
        return findGrant(tenantId, grantId)
                .map(BonusGrantBalance::remainingMinor)
                .orElse(0L);
    }

    /**
     * One of the tenant's bonus grants with its remainder, expired or not.
     *
     * <p>Empty when the id names no {@code BONUS_GRANT} entry of this tenant's
     * — which is what a caller adjusting a grant needs to tell apart from a
     * grant that exists and is spent, since one is a mistyped id and the other
     * is a correction with nothing left to take.
     */
    public Optional<BonusGrantBalance> findGrant(UUID tenantId, UUID grantId) {
        return jdbc.sql("""
                        SELECT g.id, g.amount_minor AS granted_minor, g.currency, g.expires_at, g.reason,
                               g.amount_minor + COALESCE((
                                   SELECT SUM(w.amount_minor) FROM commercial.wallet_entries w
                                    WHERE w.tenant_id = :tenantId AND w.grant_id = g.id), 0) AS remaining_minor
                          FROM commercial.wallet_entries g
                         WHERE g.tenant_id = :tenantId AND g.id = :grantId AND g.entry_type = 'BONUS_GRANT'
                        """)
                .param("tenantId", tenantId)
                .param("grantId", grantId)
                .query((row, number) -> new BonusGrantBalance(
                        row.getObject("id", UUID.class),
                        row.getLong("granted_minor"),
                        row.getLong("remaining_minor"),
                        row.getString("currency"),
                        Objects.requireNonNull(instant(row, "expires_at"), "a grant always has an expiry"),
                        row.getString("reason")))
                .optional();
    }

    /**
     * What one statement drew from the wallet and has not had given back:
     * one row per money kind and, for bonus money, per grant.
     *
     * <p>Read when a statement is voided, so each draw can be given back to
     * the exact grant or balance it came from (ADR 0095). A statement whose
     * draws have already been reversed sums to zero on every row and yields
     * nothing, which is what makes voiding idempotent against the ledger.
     */
    public List<StatementDraw> unreversedDraws(UUID tenantId, UUID statementId) {
        return jdbc.sql("""
                        SELECT money_kind, grant_id, -SUM(amount_minor) AS drawn_minor, MIN(currency) AS currency
                          FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId AND statement_id = :statementId
                           AND entry_type IN ('STATEMENT_PAYMENT', 'STATEMENT_REVERSAL')
                         GROUP BY money_kind, grant_id
                        HAVING -SUM(amount_minor) > 0
                        """)
                .param("tenantId", tenantId)
                .param("statementId", statementId)
                .query((row, number) -> new StatementDraw(
                        row.getString("money_kind"),
                        row.getObject("grant_id", UUID.class),
                        row.getLong("drawn_minor"),
                        row.getString("currency")))
                .list();
    }

    /** A candidate for the bonus expiry sweep: which grant, in which tenant, in what currency. */
    public record ExpiredGrantRef(UUID tenantId, UUID grantId, String currency) {}

    /** One statement's outstanding draw on one money kind, and on one grant when that kind is bonus. */
    public record StatementDraw(String moneyKind, @Nullable UUID grantId, long drawnMinor, String currency) {}

    /** The tenant's total live PAID balance; zero for a tenant with no entries. */
    public long paidBalance(UUID tenantId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE tenant_id = :tenantId AND money_kind = 'PAID'
                        """).param("tenantId", tenantId).query(Long.class).single();
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
                row.getObject("subscription_id", UUID.class),
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
