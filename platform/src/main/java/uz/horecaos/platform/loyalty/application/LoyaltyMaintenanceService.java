package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.loyalty.api.HeldTenderPort;
import uz.horecaos.platform.loyalty.domain.EntryType;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LotRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.ReservationRow;

/**
 * The three things time does to a points balance (ADR 0046).
 *
 * <p>Maturity: a lot inside its earn delay becomes spendable. Expiry: a lot
 * reaching {@code expires_at} with value on it is destroyed, with an entry, and
 * the value is not paid out — a balance that could be cashed out instead of
 * expiring would make the expiry rule decorative. Abandonment: a hold whose
 * tender nobody is waiting on any more returns the points, because a hold nobody
 * released is points the customer can neither see nor spend.
 *
 * <p>And one pass that is not about time at all: {@link #reconcileLedger} asks
 * whether every balance is still the sum of its own movements. It is here
 * because it needs what the other three need — no request, no tenant it was
 * authorized against, a bound on how much it looks at, and a scheduler willing
 * to run it for ever.
 *
 * <p>Batched and bounded rather than unbounded. A sweep that tries to expire six
 * months of lots in one transaction holds locks across every account in the
 * estate, and the first time it matters is the first time it is slow.
 *
 * <p>All three passes read across every tenant and take the tenant from each row
 * they touch, so every following statement carries a tenant predicate that was
 * read rather than assumed. That is deliberate: a maintenance loop has no
 * request and therefore no tenant it was authorized against, and the alternative
 * — enumerating tenants and sweeping each — would need a tenant list this module
 * does not hold and would still write exactly these rows.
 */
@Service
public class LoyaltyMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyMaintenanceService.class);

    private static final int BATCH = 500;

    private final JdbcLoyaltyStore store;
    private final PointsRedemptionService redemption;
    private final HeldTenderPort tenders;
    private final Clock clock;

    public LoyaltyMaintenanceService(JdbcLoyaltyStore store, PointsRedemptionService redemption,
            HeldTenderPort tenders, Clock clock) {
        this.store = store;
        this.redemption = redemption;
        this.tenders = tenders;
        this.clock = clock;
    }

    /** Makes matured lots spendable. Moves no value: the balance already carries them. */
    @Transactional
    public int matureLots() {
        Instant now = clock.instant();
        List<LotRow> matured = store.maturedLots(now, BATCH);
        for (LotRow lot : matured) {
            AccountRow account = accountOf(lot);
            store.activateLot(account.tenantId(), lot.id(), now);
        }
        return matured.size();
    }

    /**
     * Destroys what is left on expired lots.
     *
     * <p>One {@code EXPIRY} entry per lot, so a customer asking "where did my
     * 4 000 go" is answered with the lot, the date it was earned, and the date it
     * lapsed, rather than with a smaller number and no explanation.
     *
     * <p><strong>The amount destroyed is re-read, never carried from the batch.</strong>
     * The batch above is a plain read, and a redemption commits between it and
     * this loop as a matter of course: the customer opens the app while the sweep
     * is running, spends 3 000 of a 4 000 lot, and their checkout takes those
     * 3 000 off the lot and out of the balance in its own transaction. Expiring
     * the 4 000 this loop read then destroys the same 3 000 a second time — and
     * because the account's other lots usually cover it, the balance write
     * succeeds and nobody finds out. So the lot is re-read inside this
     * transaction, and what it still holds is what is destroyed.
     *
     * <p>The last statement of window — between that read and the close — is now
     * closed too. {@code closeLot} zeroed the row unconditionally and so could
     * not refuse when the remaining was no longer the one that was read;
     * {@link JdbcLoyaltyStore#expireLot} carries the expected remaining into its
     * WHERE clause, so the read and the write are one decision PostgreSQL makes.
     * A lot that moved matches nothing, and the next pass sees it as it now is.
     *
     * <p><strong>The batch includes lots that already say they are expired.</strong>
     * A return of points to a lot whose expiry had passed used to leave exactly
     * that — {@code EXPIRED}, with value on it, and in the customer's balance —
     * and this pass could not see the row it needed to write the entry for. The
     * return path closes such a lot itself now; the second arm of
     * {@link JdbcLoyaltyStore#expiredLots} is what reaches the rows written
     * before it did. They are repaired here rather than by a migration because
     * the repair is a balance movement and a ledger entry, and this is the code
     * that knows how to write one.
     */
    @Transactional
    public int expireLots() {
        Instant now = clock.instant();
        List<LotRow> expired = store.expiredLots(now, BATCH);
        int destroyed = 0;
        for (LotRow lot : expired) {
            AccountRow account = accountOf(lot);
            long remaining = store.findLot(account.tenantId(), lot.id())
                    .map(LotRow::remainingMinor)
                    .orElse(0L);
            if (!store.expireLot(account.tenantId(), lot.id(), remaining, now)) {
                continue;
            }
            if (remaining <= 0) {
                continue;
            }
            // The balance moves first and the entry records where it landed. The
            // other order would let a refused debit leave an entry claiming a
            // balance the account never had.
            if (!store.destroyBalance(account.tenantId(), account.id(), remaining, now)) {
                throw new IllegalStateException(
                        "A lot holds more than its account's balance: " + lot.id());
            }
            // Read back rather than arithmetic on the balance this loop started
            // with, for the same reason the amount is: a redemption that landed in
            // between changed it, and an entry stating a balance the account never
            // held is worse than no entry at all.
            long balanceAfter = store.findAccountById(account.tenantId(), account.id())
                    .map(AccountRow::balanceMinor)
                    .orElseThrow(() -> new IllegalStateException(
                            "A lot exists without an account: " + lot.id()));
            // The key names the expiry rather than the lot, and the balance
            // movement above is why the answer is not discarded. A lot reaches
            // this loop twice as a matter of design — the repair arm of
            // expiredLots exists to find one that is already EXPIRED and holds
            // value again — and under the old EXPIRY:<lot> key the second
            // destruction was refused as a duplicate, silently, after the balance
            // had already come down. See LedgerKeys.expiry.
            store.requireEntry(new JdbcLoyaltyStore.NewEntry(UUID.randomUUID(), account.tenantId(),
                    account.id(), EntryType.EXPIRY, -remaining,
                    balanceAfter, lot.id(), null, null, null, null,
                    "LOT_EXPIRED", "loyalty-expiry-sweep", null,
                    LedgerKeys.expiry(lot.id(), now), now), now);
            destroyed++;
        }
        return destroyed;
    }

    /**
     * Returns points held by a tender nobody is waiting on any more.
     *
     * <p>Goes through {@link PointsRedemptionService#release}, not around it, so
     * an abandoned hold and a failed checkout restore lots by the same code and
     * at the same original expiry.
     *
     * <p><strong>The expiry is a lease, not a fuse.</strong> The predicate behind
     * {@code staleReservations} is only "held, and older than its expiry"; it
     * knows nothing about the tender the hold was taken for. That was harmless
     * while a hold could only mean a checkout in flight, and became a money bug
     * the moment checkout started planning settlements: a cash order's balance
     * tender stays reserved until the food is handed over, which in this market
     * is routinely longer than the thirty minutes the constant was sized for. The
     * sweep reached a live confirmed order's hold, credited the points back to a
     * balance the customer could spend again, and left the tender reserved — so
     * the tenant handed over the food, collected the money net of the points, and
     * paid for the points twice.
     *
     * <p>So an expired hold is a question rather than a verdict. If
     * {@link HeldTenderPort} says the tender may still settle, the hold is
     * renewed for another lifetime and looked at again next time. If it says
     * nothing is waiting — the settlement failed, the order ended, the tender was
     * released, or there is no tender at all — the points go back, which is
     * exactly what an abandoned checkout has always meant and still means, on the
     * same thirty-minute cadence.
     *
     * <p>Renewing rather than skipping matters at scale. A skipped row keeps
     * matching the predicate, so a few hundred live orders would fill every batch
     * and the genuinely abandoned holds behind them would never be reached. A
     * renewed row drops out of the batch until its next lifetime elapses.
     *
     * @return how many holds were returned, which is what the sweeper logs. A
     *         renewal is not a release and is not counted as one
     */
    @Transactional
    public int releaseStaleHolds() {
        Instant now = clock.instant();
        List<ReservationRow> stale = store.staleReservations(now, BATCH);

        int released = 0;
        int renewed = 0;
        for (ReservationRow reservation : stale) {
            if (tenders.stillAwaitingSettlement(reservation.tenantId(), reservation.tenderId())) {
                store.renewHold(reservation.tenantId(), reservation.id(),
                        now.plus(PointsRedemptionService.HOLD_LIFETIME), now);
                renewed++;
                continue;
            }
            redemption.release(reservation.tenantId(), reservation.tenderId(), "HOLD_EXPIRED",
                    "loyalty-hold-sweep");
            released++;
        }

        if (renewed > 0) {
            log.debug("Loyalty hold sweep renewed {} holds whose tenders are still outstanding",
                    renewed);
        }
        return released;
    }

    /**
     * Asks whether every balance is still the sum of its own movements.
     *
     * <p>V0042 states this on {@code accounts.balance_minor} — "equals
     * {@code SUM(entries.amount_minor)} for this account at all times" — and
     * {@code LoyaltyQueryService.balanceDrift} computes it for one account and
     * says in its own javadoc that it must be zero. Nothing asked it of the
     * estate, and two money defects lived in the gap: a clawback that debited a
     * balance without writing an entry, and a write-off that wrote an entry
     * without moving a balance. Both passed
     * {@code balance == SUM(lots.remaining_minor)}, which is the invariant this
     * module did check everywhere; only the ledger's own identity fails both.
     *
     * <p>It reports and repairs nothing, deliberately. A repair is a balance
     * movement and a ledger entry, and neither can be written without knowing
     * which of the two sides is wrong — that is a person's judgement over the
     * account's history, not a sweep's. What a sweep can do is make sure the
     * question is asked while the answer still has an author attached to it,
     * rather than in six months by a finance team reconciling a liability report.
     *
     * <p>The log line carries the tenant, the account, and the two figures. None
     * of those is personal data under ADR 0029: an account id is a row
     * identifier, and there is no contact point, name or order in it.
     *
     * @return how many accounts are out of step, which is what the sweeper logs
     */
    @Transactional(readOnly = true)
    public int reconcileLedger() {
        List<JdbcLoyaltyStore.LedgerDrift> drifting = store.driftingAccounts(BATCH);
        for (JdbcLoyaltyStore.LedgerDrift drift : drifting) {
            log.error("Points account {} of tenant {} carries a balance of {} against movements "
                            + "summing to {}: a drift of {}. A balance is the sum of its own "
                            + "entries; one of the two is a movement that was not recorded or an "
                            + "entry that did not move.",
                    drift.accountId(), drift.tenantId(), drift.balanceMinor(), drift.ledgerMinor(),
                    drift.driftMinor());
        }
        return drifting.size();
    }

    private AccountRow accountOf(LotRow lot) {
        // The lot carries its account; the tenant comes from the account row so
        // every following statement carries a tenant predicate that was read
        // rather than assumed.
        return store.findAccountByLot(lot.id())
                .orElseThrow(() -> new IllegalStateException(
                        "A lot exists without an account: " + lot.id()));
    }
}
