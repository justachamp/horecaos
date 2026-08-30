package uz.qoida.platform.loyalty.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.loyalty.domain.AccountStatus;
import uz.qoida.platform.loyalty.domain.EntryType;
import uz.qoida.platform.loyalty.domain.LotConsumption;
import uz.qoida.platform.loyalty.domain.LotStatus;
import uz.qoida.platform.loyalty.domain.ReservationStatus;

/**
 * The points ledger, its lots, and its holds (ADR 0046).
 *
 * <p>Four rules run through every statement here.
 *
 * <p><strong>The ledger is only ever inserted into.</strong> There is no update
 * method for {@code loyalty.entries} on this class, and there could not usefully
 * be one: V0042 grants the application role SELECT and INSERT and nothing else,
 * and a trigger refuses the rest. Every correction is a new entry.
 *
 * <p><strong>The balance moves in the same statement that decides whether it
 * may.</strong> {@link #debitBalance} is one conditional UPDATE whose WHERE
 * clause carries the sufficiency test, so two checkouts against one balance are
 * separated by PostgreSQL rather than by a read the application performed a
 * moment earlier. The loser sees a row count of zero and is refused; it never
 * sees a negative balance, which a CHECK constraint also refuses.
 *
 * <p><strong>Every tenant-owned query carries the tenant predicate.</strong>
 * There is no exception on this class.
 *
 * <p><strong>Nullable columns are read with {@code getObject(col, Type.class)}.
 * </strong> {@code getLong} answers 0 for SQL NULL, and a zero
 * {@code max_accrual_minor} read off a rule that has no cap is a rule that
 * silently accrues nothing.
 */
@Repository
public class JdbcLoyaltyStore {

    private final JdbcClient jdbc;

    public JdbcLoyaltyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- accounts

    public record AccountRow(UUID id, UUID tenantId, UUID brandId, UUID customerAccountId,
            String currency, AccountStatus status, long balanceMinor, long reservedMinor,
            int version) {
    }

    /**
     * The account for one customer at one brand, creating it if this is their
     * first movement.
     *
     * <p>{@code ON CONFLICT DO NOTHING} followed by a read, rather than a read
     * followed by an insert. Two concurrent first orders both reach the insert
     * and only the unique index can decide which one is the duplicate.
     *
     * <p>The key is {@code (tenant, brand, customer)} and that is the cross-brand
     * rule in its entirety: there is no row a tenant-wide pool could occupy, so
     * points earned at one brand cannot be spent at another by any code path.
     */
    public AccountRow openAccount(UUID id, UUID tenantId, UUID brandId, UUID customerAccountId,
            String currency, Instant now) {

        jdbc.sql("""
                INSERT INTO loyalty.accounts (
                    id, tenant_id, brand_id, customer_account_id, currency,
                    status, balance_minor, reserved_minor, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :brandId, :customerAccountId, :currency,
                    'ACTIVE', 0, 0, 1, :now, :now)
                ON CONFLICT ON CONSTRAINT uq_loyalty_account_scope DO NOTHING
                """)
                .param("id", id).param("tenantId", tenantId).param("brandId", brandId)
                .param("customerAccountId", customerAccountId).param("currency", currency)
                .param("now", utc(now))
                .update();

        return findAccount(tenantId, brandId, customerAccountId).orElseThrow(
                () -> new IllegalStateException("The account was neither inserted nor found"));
    }

    public Optional<AccountRow> findAccount(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT * FROM loyalty.accounts
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND customer_account_id = :customerAccountId
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
                .param("customerAccountId", customerAccountId)
                .query(JdbcLoyaltyStore::toAccount)
                .optional();
    }

    /**
     * The account a lot belongs to.
     *
     * <p>The one query on this class that does not take a tenant, because it is
     * the sweep's way of <em>discovering</em> one: the sweeps select lots across
     * the estate by time, and every statement downstream of this carries the
     * tenant it returned.
     */
    public Optional<AccountRow> findAccountByLot(UUID lotId) {
        return jdbc.sql("""
                SELECT a.* FROM loyalty.accounts a
                  JOIN loyalty.lots l ON l.account_id = a.id AND l.tenant_id = a.tenant_id
                 WHERE l.id = :lotId
                """)
                .param("lotId", lotId)
                .query(JdbcLoyaltyStore::toAccount)
                .optional();
    }

    public Optional<AccountRow> findAccountById(UUID tenantId, UUID accountId) {
        return jdbc.sql("SELECT * FROM loyalty.accounts WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", accountId)
                .query(JdbcLoyaltyStore::toAccount)
                .optional();
    }

    /** Every points account a customer holds, one per brand, for the shared-identity read. */
    public List<AccountRow> accountsOfCustomer(UUID tenantId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT * FROM loyalty.accounts
                WHERE tenant_id = :tenantId AND customer_account_id = :customerAccountId
                ORDER BY brand_id
                """)
                .param("tenantId", tenantId).param("customerAccountId", customerAccountId)
                .query(JdbcLoyaltyStore::toAccount)
                .list();
    }

    /**
     * Takes points out of a balance and puts them under a hold, or refuses.
     *
     * <p>The sufficiency test is in the WHERE clause on purpose. A prior SELECT
     * would let two tabs both read 40 000 and both spend it; this lets exactly
     * one UPDATE match.
     *
     * @return true when the debit was taken
     */
    public boolean debitBalance(UUID tenantId, UUID accountId, long amountMinor, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.accounts
                   SET balance_minor = balance_minor - :amount,
                       reserved_minor = reserved_minor + :amount,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = 'ACTIVE'
                   AND balance_minor >= :amount
                """)
                .param("tenantId", tenantId).param("id", accountId)
                .param("amount", amountMinor).param("now", utc(now))
                .update() == 1;
    }

    /**
     * Takes points out without the account having to be spendable.
     *
     * <p>Expiry, forfeiture, a negative adjustment and a write-off all reduce a
     * balance that a suspended or closing account still carries, and refusing
     * them because the account cannot spend would leave a liability on the books
     * that nothing can clear. The floor at zero is still absolute: it is a CHECK
     * constraint as well as this WHERE clause.
     *
     * @return true when the balance had the value
     */
    public boolean destroyBalance(UUID tenantId, UUID accountId, long amountMinor, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.accounts
                   SET balance_minor = balance_minor - :amount,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND balance_minor >= :amount
                """)
                .param("tenantId", tenantId).param("id", accountId)
                .param("amount", amountMinor).param("now", utc(now))
                .update() == 1;
    }

    /** Puts points back. Used by RELEASE, REVERSAL, ACCRUAL, and a positive ADJUSTMENT. */
    public void creditBalance(UUID tenantId, UUID accountId, long amountMinor, long releasedHold,
            Instant now) {
        jdbc.sql("""
                UPDATE loyalty.accounts
                   SET balance_minor = balance_minor + :amount,
                       reserved_minor = reserved_minor - :released,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", accountId)
                .param("amount", amountMinor).param("released", releasedHold)
                .param("now", utc(now))
                .update();
    }

    /** Clears a hold without returning the points: the tender settled and they are spent. */
    public void clearHold(UUID tenantId, UUID accountId, long amountMinor, Instant now) {
        jdbc.sql("""
                UPDATE loyalty.accounts
                   SET reserved_minor = reserved_minor - :amount,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", accountId)
                .param("amount", amountMinor).param("now", utc(now))
                .update();
    }

    public void setAccountStatus(UUID tenantId, UUID accountId, AccountStatus status, Instant now) {
        jdbc.sql("""
                UPDATE loyalty.accounts
                   SET status = :status, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", accountId)
                .param("status", status.name()).param("now", utc(now))
                .update();
    }

    // -------------------------------------------------------------- entries

    /**
     * One movement.
     *
     * @param balanceAfterMinor the account balance after this entry, so a past
     *                          balance is a stored row rather than a replay
     */
    public record NewEntry(UUID id, UUID tenantId, UUID accountId, EntryType entryType,
            long amountMinor, long balanceAfterMinor, UUID lotId, UUID orderId, UUID tenderId,
            UUID ruleId, Integer ruleVersion, String reasonCode, String actor, UUID approvalId,
            String idempotencyKey, Instant occurredAt) {
    }

    /**
     * Appends a movement.
     *
     * <p>Insert only, and there is no counterpart that updates one. The grant
     * would refuse it and so would the trigger; the absence of the method is the
     * third statement of the same rule, in the place a developer looks first.
     *
     * @return true when this call recorded it; false when the idempotency key had
     *         already been used, which is a retry rather than an error
     */
    public boolean appendEntry(NewEntry entry, Instant recordedAt) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", entry.id());
        parameters.put("tenantId", entry.tenantId());
        parameters.put("accountId", entry.accountId());
        parameters.put("entryType", entry.entryType().name());
        parameters.put("amount", entry.amountMinor());
        parameters.put("balanceAfter", entry.balanceAfterMinor());
        parameters.put("lotId", entry.lotId());
        parameters.put("orderId", entry.orderId());
        parameters.put("tenderId", entry.tenderId());
        parameters.put("ruleId", entry.ruleId());
        parameters.put("ruleVersion", entry.ruleVersion());
        parameters.put("reasonCode", entry.reasonCode());
        parameters.put("actor", entry.actor());
        parameters.put("approvalId", entry.approvalId());
        parameters.put("idempotencyKey", entry.idempotencyKey());
        parameters.put("occurredAt", utc(entry.occurredAt()));
        parameters.put("recordedAt", utc(recordedAt));

        return jdbc.sql("""
                INSERT INTO loyalty.entries (
                    id, tenant_id, account_id, entry_type, amount_minor, balance_after_minor,
                    lot_id, order_id, tender_id, rule_id, rule_version,
                    reason_code, actor, approval_id, idempotency_key, occurred_at, recorded_at)
                VALUES (
                    :id, :tenantId, :accountId, :entryType, :amount, :balanceAfter,
                    :lotId, :orderId, :tenderId, :ruleId, :ruleVersion,
                    :reasonCode, :actor, :approvalId, :idempotencyKey, :occurredAt, :recordedAt)
                ON CONFLICT ON CONSTRAINT uq_loyalty_entry_idempotency DO NOTHING
                """)
                .params(parameters)
                .update() == 1;
    }

    /**
     * Appends a movement whose refusal has no honest second reading.
     *
     * <p>{@link #appendEntry}'s boolean is only ever meaningful where the caller
     * has not moved anything yet and can return. Every other caller writes the
     * entry <em>for</em> a balance movement, and there the false answer means the
     * ledger already holds a movement under this key while this transaction is
     * about to make a second one — which is the shape of both money defects this
     * ledger has had. So there is no boolean to discard: the transaction rolls
     * back and the caller's movement goes with it.
     *
     * <p>Two callers still use {@code appendEntry} directly, and both read the
     * answer before anything moves: an operator's adjustment, where a repeated
     * idempotency key is an ordinary retry and returns, and this method.
     *
     * @throws IllegalStateException when the key was already used
     */
    public void requireEntry(NewEntry entry, Instant recordedAt) {
        if (!appendEntry(entry, recordedAt)) {
            throw new IllegalStateException("A loyalty movement collided with an entry already "
                    + "recorded under key '" + entry.idempotencyKey() + "' on account "
                    + entry.accountId() + "; nothing this transaction moved is committed");
        }
    }

    public record EntryRow(UUID id, EntryType entryType, long amountMinor, long balanceAfterMinor,
            UUID lotId, UUID orderId, UUID tenderId, String reasonCode, Instant occurredAt) {
    }

    public List<EntryRow> entries(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT id, entry_type, amount_minor, balance_after_minor, lot_id, order_id,
                       tender_id, reason_code, occurred_at
                  FROM loyalty.entries
                 WHERE tenant_id = :tenantId AND account_id = :accountId
                 ORDER BY occurred_at DESC, recorded_at DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId).param("accountId", accountId).param("limit", limit)
                .query((row, number) -> new EntryRow(
                        row.getObject("id", UUID.class),
                        EntryType.valueOf(row.getString("entry_type")),
                        row.getLong("amount_minor"),
                        row.getLong("balance_after_minor"),
                        row.getObject("lot_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("tender_id", UUID.class),
                        row.getString("reason_code"),
                        instant(row, "occurred_at")))
                .list();
    }

    /** The entries one tender moved, newest first. Read by release and reversal. */
    public List<EntryRow> entriesOfTender(UUID tenantId, UUID tenderId) {
        return jdbc.sql("""
                SELECT id, entry_type, amount_minor, balance_after_minor, lot_id, order_id,
                       tender_id, reason_code, occurred_at
                  FROM loyalty.entries
                 WHERE tenant_id = :tenantId AND tender_id = :tenderId
                 ORDER BY recorded_at
                """)
                .param("tenantId", tenantId).param("tenderId", tenderId)
                .query((row, number) -> new EntryRow(
                        row.getObject("id", UUID.class),
                        EntryType.valueOf(row.getString("entry_type")),
                        row.getLong("amount_minor"),
                        row.getLong("balance_after_minor"),
                        row.getObject("lot_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("tender_id", UUID.class),
                        row.getString("reason_code"),
                        instant(row, "occurred_at")))
                .list();
    }

    /**
     * The balance as the ledger says it is.
     *
     * <p>The definition of the number. {@code accounts.balance_minor} is a cache
     * of this, and the reconciliation that compares the two is the test that the
     * cache never became the authority.
     */
    public long ledgerBalance(UUID tenantId, UUID accountId) {
        Long total = jdbc.sql("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM loyalty.entries
                 WHERE tenant_id = :tenantId AND account_id = :accountId
                """)
                .param("tenantId", tenantId).param("accountId", accountId)
                .query(Long.class).single();
        return total == null ? 0L : total;
    }

    // ------------------------------------------------------------ clawbacks

    /**
     * One refunded order's accrual clawback (V0079).
     *
     * @param recoveredMinor  the part the balance covered, which has its own
     *                        {@code ADJUSTMENT} entry
     * @param writtenOffMinor the part it did not, charged to the brand. Not a
     *                        ledger entry: there is no balance left for it to
     *                        move, and an entry that moves no balance is what
     *                        made {@code balance_minor = SUM(amount_minor)} false
     */
    public record ClawbackRow(UUID id, UUID tenantId, UUID brandId, UUID accountId, UUID orderId,
            long requestedMinor, long recoveredMinor, long writtenOffMinor, String reasonCode,
            String actor, Instant occurredAt) {
    }

    /**
     * Records a clawback, once per order.
     *
     * <p>The gate rather than a note taken afterwards. It is the first write of
     * the clawback transaction, so a redelivery is refused here — by
     * {@code uq_loyalty_clawback_order}, before any balance moves — rather than
     * by a boolean somebody downstream remembered to read.
     *
     * @return true when this call recorded it; false when this order's clawback
     *         is already recorded, which is a redelivery rather than an error
     */
    public boolean recordClawback(ClawbackRow clawback, Instant recordedAt) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", clawback.id());
        parameters.put("tenantId", clawback.tenantId());
        parameters.put("brandId", clawback.brandId());
        parameters.put("accountId", clawback.accountId());
        parameters.put("orderId", clawback.orderId());
        parameters.put("requested", clawback.requestedMinor());
        parameters.put("recovered", clawback.recoveredMinor());
        parameters.put("writtenOff", clawback.writtenOffMinor());
        parameters.put("reasonCode", clawback.reasonCode());
        parameters.put("actor", clawback.actor());
        parameters.put("occurredAt", utc(clawback.occurredAt()));
        parameters.put("recordedAt", utc(recordedAt));

        return jdbc.sql("""
                INSERT INTO loyalty.clawbacks (
                    id, tenant_id, brand_id, account_id, order_id,
                    requested_minor, recovered_minor, written_off_minor,
                    reason_code, actor, occurred_at, recorded_at)
                VALUES (
                    :id, :tenantId, :brandId, :accountId, :orderId,
                    :requested, :recovered, :writtenOff,
                    :reasonCode, :actor, :occurredAt, :recordedAt)
                ON CONFLICT ON CONSTRAINT uq_loyalty_clawback_order DO NOTHING
                """)
                .params(parameters)
                .update() == 1;
    }

    /** What a redelivered clawback answers with, rather than recomputing it. */
    public Optional<ClawbackRow> findClawback(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT * FROM loyalty.clawbacks
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query((row, number) -> new ClawbackRow(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("account_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getLong("requested_minor"),
                        row.getLong("recovered_minor"),
                        row.getLong("written_off_minor"),
                        row.getString("reason_code"),
                        row.getString("actor"),
                        instant(row, "occurred_at")))
                .optional();
    }

    // ---------------------------------------------------------- reconciliation

    /**
     * @param driftMinor what the cached balance carries that the movements do not
     *                   explain. Positive is a balance with no entry behind it;
     *                   negative is an entry with no movement behind it
     */
    public record LedgerDrift(UUID tenantId, UUID accountId, long balanceMinor, long ledgerMinor,
            long driftMinor) {
    }

    /**
     * Every account whose cached balance is not the sum of its own movements.
     *
     * <p>The invariant V0042 states in prose on {@code accounts.balance_minor} —
     * "equals {@code SUM(entries.amount_minor)} for this account at all times" —
     * asked as a question. Nothing asked it. Two money defects passed
     * {@code balance == SUM(lots.remaining_minor)}, which is a different and
     * weaker claim: a debit taken with no entry written moves the balance and the
     * lots together and leaves that equality green, and an entry written with no
     * debit taken leaves it green as well.
     *
     * <p>Signed and per account, never summed across the estate. The two halves
     * of the clawback defect drifted in opposite directions on different
     * accounts, so a single total over every account could read zero while both
     * were broken.
     *
     * <p>No tenant predicate, deliberately, and for the same reason as
     * {@link #expiredLots} and {@link #staleReservations}: a reconciliation has no
     * request behind it and therefore no tenant it was authorized against. It
     * reads and reports; it writes nothing, and every row it returns names the
     * tenant it was found in.
     */
    public List<LedgerDrift> driftingAccounts(int limit) {
        return jdbc.sql("""
                SELECT a.tenant_id, a.id, a.balance_minor,
                       COALESCE(SUM(e.amount_minor), 0) AS ledger_minor
                  FROM loyalty.accounts a
                  LEFT JOIN loyalty.entries e
                         ON e.tenant_id = a.tenant_id AND e.account_id = a.id
                 GROUP BY a.tenant_id, a.id, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(e.amount_minor), 0)
                 ORDER BY a.tenant_id, a.id
                 LIMIT :limit
                """)
                .param("limit", limit)
                .query((row, number) -> {
                    long balance = row.getLong("balance_minor");
                    long ledger = row.getLong("ledger_minor");
                    return new LedgerDrift(
                            row.getObject("tenant_id", UUID.class),
                            row.getObject("id", UUID.class),
                            balance, ledger, Math.subtractExact(balance, ledger));
                })
                .list();
    }

    // ----------------------------------------------------------------- lots

    public record LotRow(UUID id, UUID accountId, long grantedMinor, long remainingMinor,
            Instant earnsAt, Instant expiresAt, LotStatus status) {
    }

    public void insertLot(UUID id, UUID tenantId, UUID accountId, UUID sourceEntryId,
            long grantedMinor, Instant earnsAt, Instant expiresAt, LotStatus status, Instant now) {
        jdbc.sql("""
                INSERT INTO loyalty.lots (
                    id, tenant_id, account_id, source_entry_id, granted_minor, remaining_minor,
                    earns_at, expires_at, status, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :accountId, :sourceEntryId, :granted, :granted,
                    :earnsAt, :expiresAt, :status, 1, :now, :now)
                """)
                .param("id", id).param("tenantId", tenantId).param("accountId", accountId)
                .param("sourceEntryId", sourceEntryId).param("granted", grantedMinor)
                .param("earnsAt", utc(earnsAt)).param("expiresAt", utc(expiresAt))
                .param("status", status.name()).param("now", utc(now))
                .update();
    }

    /**
     * The lots a redemption may consume, in consumption order.
     *
     * <p>{@code FOR UPDATE} so a concurrent redemption against the same account
     * queues behind this one rather than planning against lot balances that are
     * about to change. The account debit is the real gate; this stops two winners
     * of two different accounts' races from interleaving lot writes.
     */
    public List<LotConsumption.AvailableLot> availableLots(UUID tenantId, UUID accountId,
            Instant asOf) {
        return jdbc.sql("""
                SELECT id, remaining_minor, expires_at, earns_at
                  FROM loyalty.lots
                 WHERE tenant_id = :tenantId AND account_id = :accountId
                   AND status = 'ACTIVE'
                   AND remaining_minor > 0
                   AND earns_at <= :asOf
                   AND expires_at > :asOf
                 ORDER BY expires_at, earns_at, id
                 FOR UPDATE
                """)
                .param("tenantId", tenantId).param("accountId", accountId).param("asOf", utc(asOf))
                .query((row, number) -> new LotConsumption.AvailableLot(
                        row.getObject("id", UUID.class),
                        row.getLong("remaining_minor"),
                        instant(row, "expires_at"),
                        instant(row, "earns_at")))
                .list();
    }

    /**
     * What the account could actually spend right now.
     *
     * <p>Not the same as {@code balance_minor}, and the difference is the earn
     * delay: a lot granted an hour ago is in the balance and is not yet
     * spendable. Showing the balance as though it were spendable is how a
     * storefront offers a redemption that checkout then refuses.
     */
    public long spendableMinor(UUID tenantId, UUID accountId, Instant asOf) {
        Long total = jdbc.sql("""
                SELECT COALESCE(SUM(remaining_minor), 0) FROM loyalty.lots
                 WHERE tenant_id = :tenantId AND account_id = :accountId
                   AND status = 'ACTIVE' AND earns_at <= :asOf AND expires_at > :asOf
                """)
                .param("tenantId", tenantId).param("accountId", accountId).param("asOf", utc(asOf))
                .query(Long.class).single();
        return total == null ? 0L : total;
    }

    public Optional<LotRow> findLot(UUID tenantId, UUID lotId) {
        return jdbc.sql("SELECT * FROM loyalty.lots WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", lotId)
                .query(JdbcLoyaltyStore::toLot)
                .optional();
    }

    /**
     * Takes value off a lot, refusing to take more than it holds.
     *
     * @return true when the lot had the value
     */
    public boolean consumeLot(UUID tenantId, UUID lotId, long amountMinor, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.lots
                   SET remaining_minor = remaining_minor - :amount,
                       status = CASE WHEN remaining_minor - :amount = 0 THEN 'CONSUMED'
                                     ELSE status END,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND remaining_minor >= :amount
                """)
                .param("tenantId", tenantId).param("id", lotId)
                .param("amount", amountMinor).param("now", utc(now))
                .update() == 1;
    }

    /**
     * What a lot looked like the instant value was put back on it.
     *
     * @param status          the status the restore left it in
     * @param remainingMinor  what it holds after the restore, which is what an
     *                        immediate expiry has to destroy — not the amount
     *                        that was returned, because a lot that lapsed while
     *                        part of it was held still carries the unheld part
     */
    public record RestoredLot(LotStatus status, long remainingMinor) {

        /**
         * True when the points came back to a lot whose life had already run out.
         *
         * <p>The caller must finish the job in the same transaction. A lot left
         * {@code EXPIRED} with value on it is value the customer can see and can
         * never spend: {@code availableLots} refuses it and, before V0067, the
         * expiry sweep could not see it either.
         */
        public boolean lapsed() {
            return status == LotStatus.EXPIRED;
        }

        /**
         * True when the points came back to a lot that had already been
         * destroyed by a closure or an ADR 0029 erasure.
         *
         * <p>The other half of the same obligation as {@link #lapsed()}, and the
         * one that was missing. A {@code FORFEITED} lot used to come back
         * {@code ACTIVE}: closure destroyed the value with a {@code FORFEITURE}
         * entry, a later cancellation returned it, and the relabelled lot made it
         * spendable again on an account whose customer no longer exists. The
         * status is no longer moved off {@code FORFEITED} by a restore, so the
         * caller can see what it is holding and must finish the job in the same
         * transaction — the value goes back out with a second
         * {@code FORFEITURE}, because the money moved and the ledger has to say
         * where it went.
         */
        public boolean forfeited() {
            return status == LotStatus.FORFEITED;
        }
    }

    /**
     * Puts value back on a lot at its original expiry.
     *
     * <p>{@code expires_at} is not touched, in this statement or anywhere else.
     * Points three days from expiry when spent are three days from expiry when
     * returned; resetting the clock is a giveaway that compounds on every refund
     * and that nobody would notice until the liability stopped decaying.
     *
     * <p>A lot already past its expiry comes back {@code EXPIRED} rather than
     * {@code ACTIVE}, so a refund does not resurrect value the customer had
     * already lost. <strong>That is only half of the movement, and the caller
     * owes the other half.</strong> This used to be the whole of it, and the
     * result was value credited to {@code accounts.balance_minor} and
     * simultaneously orphaned: no redemption could reach it, because
     * {@link #availableLots} requires {@code ACTIVE}, and no sweep could destroy
     * it, because {@link #expiredLots} looked only at {@code PENDING} and
     * {@code ACTIVE}. The invariant {@code balance == SUM(remaining)} still held
     * throughout, which is exactly why nothing caught it. The status is returned
     * here rather than discarded so the caller cannot forget.
     *
     * <p><strong>A {@code FORFEITED} lot stays forfeited.</strong> The
     * {@code CASE} had three arms and none of them was this one, so a lot
     * destroyed by an account closure or an ADR 0029 erasure came back
     * {@code ACTIVE} the first time a cancellation returned points to it — and
     * {@code creditBalance} asked nothing about the account's status, so a
     * {@code CLOSED} account was handed a spendable balance whose customer no
     * longer exists. {@code RestoredLot.lapsed()} could not see it either: it
     * tests {@code EXPIRED}, and this shape is not expiry. The value still lands
     * on the lot, because the lot's arithmetic has to stay honest and the caller
     * has to have something to destroy; what it does not do is become spendable
     * again. {@link RestoredLot#forfeited()} is how the caller is told.
     *
     * @return the state the lot is in now, including what it holds
     */
    public RestoredLot restoreLot(UUID tenantId, UUID lotId, long amountMinor, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.lots
                   SET remaining_minor = remaining_minor + :amount,
                       status = CASE WHEN status = 'FORFEITED' THEN 'FORFEITED'
                                     WHEN expires_at <= :now THEN 'EXPIRED'
                                     WHEN earns_at > :now THEN 'PENDING'
                                     ELSE 'ACTIVE' END,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                RETURNING status, remaining_minor
                """)
                .param("tenantId", tenantId).param("id", lotId)
                .param("amount", amountMinor).param("now", utc(now))
                .query((row, number) -> new RestoredLot(
                        LotStatus.valueOf(row.getString("status")),
                        row.getLong("remaining_minor")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "No such lot to restore points to: " + lotId));
    }

    /** Lots whose earn delay has elapsed. The sweep that makes deferred accrual spendable. */
    public List<LotRow> maturedLots(Instant asOf, int limit) {
        return jdbc.sql("""
                SELECT * FROM loyalty.lots
                 WHERE status = 'PENDING' AND earns_at <= :asOf AND expires_at > :asOf
                 ORDER BY earns_at
                 LIMIT :limit
                """)
                .param("asOf", utc(asOf)).param("limit", limit)
                .query(JdbcLoyaltyStore::toLot)
                .list();
    }

    public void activateLot(UUID tenantId, UUID lotId, Instant now) {
        jdbc.sql("""
                UPDATE loyalty.lots
                   SET status = 'ACTIVE', version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING'
                """)
                .param("tenantId", tenantId).param("id", lotId).param("now", utc(now))
                .update();
    }

    /**
     * Lots past their expiry with value still on them. Each becomes an EXPIRY entry.
     *
     * <p>Two arms, because there are two ways a lot can be past its expiry and
     * still owe the ledger an entry. The first is the ordinary one: a lot that
     * is {@code PENDING} or {@code ACTIVE} and has simply reached its date. The
     * second is a repair: a lot already <em>labelled</em> {@code EXPIRED} — or
     * {@code CONSUMED} — that nevertheless holds value. That state was
     * unreachable to this query for as long as it read only the first arm, and
     * a return to an already-expired lot produced exactly it, so the value sat
     * in the customer's balance for ever with no entry ever coming for it.
     * {@link #restoreLot}'s callers no longer create it; this arm is what
     * reaches the rows created before they stopped.
     *
     * <p>{@code FORFEITED} is deliberately excluded. Closure and ADR 0029
     * erasure destroy a lot with a {@code FORFEITURE} entry of its own, and
     * re-labelling one {@code EXPIRED} here would rewrite that fact. Value left
     * on a forfeited lot is the one shape this sweep will not touch, and it is
     * the shape {@link #unbackedValueMinor} is watching for.
     *
     * <p>A UNION rather than an OR so each arm can use its own partial index —
     * {@code ix_loyalty_lot_expiry_sweep} for the first, V0067's
     * {@code ix_loyalty_lot_expiry_repair} for the second. A single predicate
     * with an OR in it is a sequential scan of every lot in the estate, once an
     * hour, for ever. The arms select disjoint statuses, so no lot appears twice.
     */
    public List<LotRow> expiredLots(Instant asOf, int limit) {
        return jdbc.sql("""
                SELECT * FROM (
                    (SELECT * FROM loyalty.lots
                      WHERE status IN ('PENDING', 'ACTIVE')
                        AND expires_at <= :asOf
                      ORDER BY expires_at
                      LIMIT :limit)
                    UNION ALL
                    (SELECT * FROM loyalty.lots
                      WHERE status NOT IN ('PENDING', 'ACTIVE', 'FORFEITED')
                        AND remaining_minor > 0
                        AND expires_at <= :asOf
                      ORDER BY expires_at
                      LIMIT :limit)
                ) AS due
                 ORDER BY expires_at
                 LIMIT :limit
                """)
                .param("asOf", utc(asOf)).param("limit", limit)
                .query(JdbcLoyaltyStore::toLot)
                .list();
    }

    /**
     * Closes a lot at its expiry, and only if it still holds what the caller read.
     *
     * <p>The predicate {@link #closeLot} could not offer. The expiry sweep reads
     * a lot, decides how much to destroy, and then writes; a redemption that
     * commits between those two statements takes value off the lot, and closing
     * it unconditionally destroys that value a second time. Carrying the
     * expected remaining into the WHERE clause collapses the read and the write
     * into one decision PostgreSQL makes: if the lot moved, this matches nothing
     * and the caller leaves it for the next pass.
     *
     * <p>{@code expires_at <= :now} is in the clause as well, so this can never
     * be the statement that expires a lot which still has life in it, whatever a
     * caller believes.
     *
     * @return true when this call closed it
     */
    public boolean expireLot(UUID tenantId, UUID lotId, long expectedRemainingMinor, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.lots
                   SET remaining_minor = 0, status = 'EXPIRED',
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND expires_at <= :now
                   AND remaining_minor = :expected
                   AND status <> 'FORFEITED'
                """)
                .param("tenantId", tenantId).param("id", lotId)
                .param("expected", expectedRemainingMinor).param("now", utc(now))
                .update() == 1;
    }

    /**
     * Destroys a lot whose account is gone, whatever status it is in now.
     *
     * <p>Neither {@link #closeLot} nor {@link #expireLot} can be this statement.
     * {@code closeLot} requires {@code PENDING} or {@code ACTIVE}, which a lot
     * already forfeited by the closure is not; {@code expireLot} requires
     * {@code expires_at <= now}, which a lot with months of life left is not, and
     * it would write the wrong status besides — the points were not lost to time,
     * they were lost to the account being closed, and a liability report has to be
     * able to tell those two apart.
     *
     * <p>The expected remaining is in the WHERE clause for the same reason it is
     * in {@code expireLot}: the caller read the row a statement ago, and a lot
     * that moved in between must match nothing rather than have its value
     * destroyed twice.
     *
     * @return true when this call closed it
     */
    public boolean forfeitLot(UUID tenantId, UUID lotId, long expectedRemainingMinor, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.lots
                   SET remaining_minor = 0, status = 'FORFEITED',
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND remaining_minor = :expected
                """)
                .param("tenantId", tenantId).param("id", lotId)
                .param("expected", expectedRemainingMinor).param("now", utc(now))
                .update() == 1;
    }

    /**
     * Value counted in a balance that no lot will ever settle.
     *
     * <p>The invariant nobody was checking. {@code LoyaltyMaintenanceService}
     * guards one direction — a lot holding more than its account's balance — and
     * this is the other: points the customer can see, cannot spend, and for which
     * no {@code EXPIRY} or {@code FORFEITURE} entry is ever coming. A lot in a
     * terminal status with value on it is that, by definition of the statuses:
     * {@link #availableLots} refuses every one of them, and the only sweep that
     * could still act on it is the repair arm of {@link #expiredLots}, which is
     * a repair and not a resting place.
     *
     * <p>Note what this is <em>not</em>: it is not
     * {@code balance == SUM(remaining)}. That equality held throughout the defect
     * this method exists to catch, which is why it caught nothing.
     *
     * @return the total still sitting on {@code CONSUMED}, {@code EXPIRED} and
     *         {@code FORFEITED} lots, which must be zero
     */
    public long unbackedValueMinor(UUID tenantId, UUID accountId) {
        Long total = jdbc.sql("""
                SELECT COALESCE(SUM(remaining_minor), 0) FROM loyalty.lots
                 WHERE tenant_id = :tenantId AND account_id = :accountId
                   AND status NOT IN ('PENDING', 'ACTIVE')
                   AND remaining_minor > 0
                """)
                .param("tenantId", tenantId).param("accountId", accountId)
                .query(Long.class).single();
        return total == null ? 0L : total;
    }

    public boolean closeLot(UUID tenantId, UUID lotId, LotStatus status, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.lots
                   SET remaining_minor = 0, status = :status,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status IN ('PENDING', 'ACTIVE')
                """)
                .param("tenantId", tenantId).param("id", lotId)
                .param("status", status.name()).param("now", utc(now))
                .update() == 1;
    }

    /** Every lot with value left, for closure forfeiture and for the liability report. */
    public List<LotRow> openLots(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT * FROM loyalty.lots
                 WHERE tenant_id = :tenantId AND account_id = :accountId
                   AND status IN ('PENDING', 'ACTIVE') AND remaining_minor > 0
                 ORDER BY expires_at, earns_at, id
                """)
                .param("tenantId", tenantId).param("accountId", accountId)
                .query(JdbcLoyaltyStore::toLot)
                .list();
    }

    // --------------------------------------------------------- reservations

    public record ReservationRow(UUID id, UUID tenantId, UUID accountId, UUID orderId,
            UUID tenderId, long amountMinor, ReservationStatus status, Instant expiresAt,
            int version) {
    }

    public void insertReservation(ReservationRow reservation, String idempotencyKey, Instant now) {
        jdbc.sql("""
                INSERT INTO loyalty.reservations (
                    id, tenant_id, account_id, order_id, tender_id, amount_minor,
                    status, expires_at, idempotency_key, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :accountId, :orderId, :tenderId, :amount,
                    :status, :expiresAt, :idempotencyKey, 1, :now, :now)
                """)
                .param("id", reservation.id()).param("tenantId", reservation.tenantId())
                .param("accountId", reservation.accountId()).param("orderId", reservation.orderId())
                .param("tenderId", reservation.tenderId())
                .param("amount", reservation.amountMinor())
                .param("status", reservation.status().name())
                .param("expiresAt", utc(reservation.expiresAt()))
                .param("idempotencyKey", idempotencyKey).param("now", utc(now))
                .update();
    }

    public void recordReservationLot(UUID reservationId, UUID lotId, UUID tenantId,
            long amountMinor) {
        jdbc.sql("""
                INSERT INTO loyalty.reservation_lots (reservation_id, lot_id, tenant_id, amount_minor)
                VALUES (:reservationId, :lotId, :tenantId, :amount)
                """)
                .param("reservationId", reservationId).param("lotId", lotId)
                .param("tenantId", tenantId).param("amount", amountMinor)
                .update();
    }

    public Optional<ReservationRow> findReservationByTender(UUID tenantId, UUID tenderId) {
        return jdbc.sql("""
                SELECT * FROM loyalty.reservations
                 WHERE tenant_id = :tenantId AND tender_id = :tenderId
                """)
                .param("tenantId", tenantId).param("tenderId", tenderId)
                .query(JdbcLoyaltyStore::toReservation)
                .optional();
    }

    /** What the hold took from each lot, in the order it took it. */
    public List<LotConsumption> reservationLots(UUID tenantId, UUID reservationId) {
        return jdbc.sql("""
                SELECT rl.lot_id, rl.amount_minor, l.expires_at
                  FROM loyalty.reservation_lots rl
                  JOIN loyalty.lots l ON l.id = rl.lot_id AND l.tenant_id = rl.tenant_id
                 WHERE rl.tenant_id = :tenantId AND rl.reservation_id = :reservationId
                 ORDER BY l.expires_at, l.earns_at, l.id
                """)
                .param("tenantId", tenantId).param("reservationId", reservationId)
                .query((row, number) -> new LotConsumption(
                        row.getObject("lot_id", UUID.class),
                        row.getLong("amount_minor"),
                        instant(row, "expires_at")))
                .list();
    }

    /**
     * Moves a hold to a terminal state, naming the state it expects.
     *
     * @return true when this call made the transition, so a repeated release or a
     *         release racing a settlement moves the points exactly once
     */
    public boolean transitionReservation(UUID tenantId, UUID reservationId,
            ReservationStatus from, ReservationStatus to, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.reservations
                   SET status = :to, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = :from
                """)
                .param("tenantId", tenantId).param("id", reservationId)
                .param("from", from.name()).param("to", to.name()).param("now", utc(now))
                .update() == 1;
    }

    /**
     * Holds that have outlived their lifetime, oldest first.
     *
     * <p>Candidates and not verdicts. This predicate cannot see the tender a hold
     * was taken for, so it cannot tell an abandoned checkout from a cash order
     * still on the road; {@code LoyaltyMaintenanceService} asks payments about
     * each row before returning anything. Narrowing the predicate here instead
     * would mean this module learning to read another module's order statuses,
     * which is the dependency ADR 0046 keeps out of the ledger.
     *
     * <p>No tenant predicate, deliberately, and the same shape as {@link
     * #maturedLots} and {@link #expiredLots}: a maintenance sweep has no request
     * behind it and therefore no tenant it was authorized against. Every write
     * that follows takes its tenant from the row that was read, so no statement
     * ever crosses a tenant boundary even though this read spans them.
     */
    public List<ReservationRow> staleReservations(Instant asOf, int limit) {
        return jdbc.sql("""
                SELECT * FROM loyalty.reservations
                 WHERE status = 'HELD' AND expires_at <= :asOf
                 ORDER BY expires_at
                 LIMIT :limit
                """)
                .param("asOf", utc(asOf)).param("limit", limit)
                .query(JdbcLoyaltyStore::toReservation)
                .list();
    }

    /**
     * Extends a hold that is still waiting on its tender.
     *
     * <p>Conditional on {@code HELD}, so a hold settled or released between the
     * sweep's read and this statement is not quietly resurrected with a fresh
     * expiry.
     *
     * @return true when this call moved the expiry
     */
    public boolean renewHold(UUID tenantId, UUID reservationId, Instant expiresAt, Instant now) {
        return jdbc.sql("""
                UPDATE loyalty.reservations
                   SET expires_at = :expiresAt, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'HELD'
                """)
                .param("tenantId", tenantId).param("id", reservationId)
                .param("expiresAt", utc(expiresAt)).param("now", utc(now))
                .update() == 1;
    }

    // ---------------------------------------------------- rules and policies

    public record AccrualRuleRow(UUID id, int version, int rateBasisPoints, Long maxAccrualMinor,
            int earnDelayHours, int lotLifetimeDays, int expiryWarningDays) {
    }

    /**
     * The accrual rule in force for a brand at an instant.
     *
     * <p>Narrowest scope first: a channel rule beats a location rule beats the
     * brand's. Ordered rather than filtered in Java so that the rule an entry
     * snapshots is decided by one query a report can repeat.
     */
    public Optional<AccrualRuleRow> accrualRule(UUID tenantId, UUID brandId, UUID locationId,
            UUID channelId, Instant asOf) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("locationId", locationId);
        parameters.put("channelId", channelId);
        parameters.put("asOf", utc(asOf));

        return jdbc.sql("""
                SELECT id, version, rate_basis_points, max_accrual_minor, earn_delay_hours,
                       lot_lifetime_days, expiry_warning_days
                  FROM loyalty.accrual_rules
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND status = 'ACTIVE'
                   AND valid_from <= :asOf
                   AND (valid_until IS NULL OR valid_until > :asOf)
                   AND (scope_type = 'BRAND'
                        OR (scope_type = 'LOCATION' AND scope_id = :locationId)
                        OR (scope_type = 'CHANNEL' AND scope_id = :channelId))
                 ORDER BY CASE scope_type WHEN 'CHANNEL' THEN 0 WHEN 'LOCATION' THEN 1 ELSE 2 END,
                          valid_from DESC
                 LIMIT 1
                """)
                .params(parameters)
                .query((row, number) -> new AccrualRuleRow(
                        row.getObject("id", UUID.class),
                        row.getInt("version"),
                        row.getInt("rate_basis_points"),
                        row.getObject("max_accrual_minor", Long.class),
                        row.getInt("earn_delay_hours"),
                        row.getInt("lot_lifetime_days"),
                        row.getInt("expiry_warning_days")))
                .optional();
    }

    public record RedemptionPolicyRow(UUID id, int version, int maxShareBasisPoints,
            long minOrderMinor, boolean excludesDeliveryFee, List<String> allowedChannels) {
    }

    public Optional<RedemptionPolicyRow> redemptionPolicy(UUID tenantId, UUID brandId,
            Instant asOf) {
        return jdbc.sql("""
                SELECT id, version, max_share_basis_points, min_order_minor,
                       excludes_delivery_fee, allowed_channels
                  FROM loyalty.redemption_policies
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND status = 'ACTIVE'
                   AND valid_from <= :asOf
                   AND (valid_until IS NULL OR valid_until > :asOf)
                 ORDER BY valid_from DESC
                 LIMIT 1
                """)
                .param("tenantId", tenantId).param("brandId", brandId).param("asOf", utc(asOf))
                .query((row, number) -> new RedemptionPolicyRow(
                        row.getObject("id", UUID.class),
                        row.getInt("version"),
                        row.getInt("max_share_basis_points"),
                        row.getLong("min_order_minor"),
                        row.getBoolean("excludes_delivery_fee"),
                        channels(row)))
                .optional();
    }

    // ----------------------------------------------------------- order facts

    /**
     * The order facts a redemption is checked against.
     *
     * <p>Read here rather than passed in by the caller. "The order's customer is
     * this account's customer" is the non-transferability rule, and a rule
     * checked against a value the caller supplied is a rule the caller can lie
     * about.
     */
    public record OrderFacts(UUID brandId, UUID customerAccountId, String channelCode,
            String currency, long totalMinor, long feeMinor) {
    }

    public Optional<OrderFacts> orderFacts(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT brand_id, customer_account_id, channel_code_snapshot, currency,
                       total_minor, fee_minor
                  FROM ordering.orders
                 WHERE tenant_id = :tenantId AND id = :orderId
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query((row, number) -> new OrderFacts(
                        row.getObject("brand_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("channel_code_snapshot"),
                        row.getString("currency"),
                        row.getLong("total_minor"),
                        row.getLong("fee_minor")))
                .optional();
    }

    // --------------------------------------------------------- liability

    /**
     * @param outstandingMinor what the tenant would owe if every point were spent
     * @param heldMinor        the part currently held by an unsettled tender
     */
    public record LiabilityRow(UUID brandId, String currency, long outstandingMinor,
            long heldMinor, long accountCount) {
    }

    /**
     * What the brand owes, per brand and never pooled.
     *
     * <p>Per brand because a brand's outstanding points are the liability of the
     * legal entity that will honour them, and ADR 0038 establishes that one
     * tenant routinely contains several taxpayers. A single tenant figure would
     * be a number no one company could put on its books.
     */
    public List<LiabilityRow> liability(UUID tenantId) {
        return jdbc.sql("""
                SELECT brand_id, currency,
                       COALESCE(SUM(balance_minor), 0) AS outstanding_minor,
                       COALESCE(SUM(reserved_minor), 0) AS held_minor,
                       COUNT(*) AS account_count
                  FROM loyalty.accounts
                 WHERE tenant_id = :tenantId AND status <> 'CLOSED'
                 GROUP BY brand_id, currency
                 ORDER BY brand_id
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new LiabilityRow(
                        row.getObject("brand_id", UUID.class),
                        row.getString("currency"),
                        row.getLong("outstanding_minor"),
                        row.getLong("held_minor"),
                        row.getLong("account_count")))
                .list();
    }

    // ------------------------------------------------------------- mapping

    private static AccountRow toAccount(ResultSet row, int number) throws SQLException {
        return new AccountRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("currency"),
                AccountStatus.valueOf(row.getString("status")),
                row.getLong("balance_minor"),
                row.getLong("reserved_minor"),
                row.getInt("version"));
    }

    private static LotRow toLot(ResultSet row, int number) throws SQLException {
        return new LotRow(
                row.getObject("id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getLong("granted_minor"),
                row.getLong("remaining_minor"),
                instant(row, "earns_at"),
                instant(row, "expires_at"),
                LotStatus.valueOf(row.getString("status")));
    }

    private static ReservationRow toReservation(ResultSet row, int number) throws SQLException {
        return new ReservationRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getObject("tender_id", UUID.class),
                row.getLong("amount_minor"),
                ReservationStatus.valueOf(row.getString("status")),
                instant(row, "expires_at"),
                row.getInt("version"));
    }

    private static List<String> channels(ResultSet row) throws SQLException {
        java.sql.Array array = row.getArray("allowed_channels");
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
