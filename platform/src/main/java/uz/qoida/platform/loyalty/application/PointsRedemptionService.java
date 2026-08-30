package uz.qoida.platform.loyalty.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.loyalty.api.PointsRedemptionPort;
import uz.qoida.platform.loyalty.domain.AccountStatus;
import uz.qoida.platform.loyalty.domain.EntryType;
import uz.qoida.platform.loyalty.domain.LotConsumption;
import uz.qoida.platform.loyalty.domain.RedemptionLimit;
import uz.qoida.platform.loyalty.domain.ReservationStatus;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.EntryRow;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.OrderFacts;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.RedemptionPolicyRow;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.ReservationRow;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Spending points, and the four ways a redemption can end (ADR 0046).
 *
 * <p>This class is where "points are not money" stops being a sentence. Three
 * checks run inside the reserving transaction and none of them can be satisfied
 * from outside it:
 *
 * <ul>
 *   <li>the order's {@code customer_account_id} equals the account's, so a
 *       redemption cannot be spent on somebody else's order and a guest checkout
 *       cannot redeem at all;</li>
 *   <li>the order's {@code brand_id} equals the account's, which is the
 *       cross-brand rule at the point it would otherwise be broken; and</li>
 *   <li>the amount is inside the redemption cap resolved for this order, which
 *       itself can never reach the whole total.</li>
 * </ul>
 *
 * <p>The order facts are read here rather than accepted from the caller. A rule
 * checked against a value the caller supplied is a rule the caller can lie
 * about, and the caller in the interesting case is an operator-assisted checkout
 * under ADR 0039.
 *
 * <p>The hold is a debit. Two carts in two tabs must not both spend the same
 * 40 000, and {@link JdbcLoyaltyStore#debitBalance} decides that in one
 * conditional UPDATE rather than in a read the application performed a moment
 * earlier.
 */
@Service
public class PointsRedemptionService implements PointsRedemptionPort {

    private static final Logger log = LoggerFactory.getLogger(PointsRedemptionService.class);

    /**
     * How long a hold survives before somebody has to justify it.
     *
     * <p>Long enough for a slow provider redirect, short enough that an abandoned
     * cart does not sit on a customer's balance for an evening. That was and
     * remains the sizing.
     *
     * <p>What changed is that reaching it is no longer a verdict. Once checkout
     * began planning settlements, a hold could also mean a cash order's balance
     * tender, outstanding until the food is handed over — forty to sixty minutes
     * in this market, and longer behind an approval deadline or a scheduled
     * pre-order. So {@link LoyaltyMaintenanceService} asks whether the tender is
     * still expected to settle and renews the hold for another lifetime when it
     * is. A hold nobody is waiting on is returned on exactly this cadence, which
     * is the abandonment rule this constant has always been.
     */
    static final Duration HOLD_LIFETIME = Duration.ofMinutes(30);

    private final JdbcLoyaltyStore store;
    private final LoyaltyPolicyService policies;
    private final Clock clock;

    public PointsRedemptionService(JdbcLoyaltyStore store, LoyaltyPolicyService policies,
            Clock clock) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public RedemptionOffer quote(RedemptionQuery query) {
        Optional<AccountRow> account = store.findAccount(
                query.tenantId(), query.brandId(), query.customerAccountId());
        if (account.isEmpty()) {
            return new RedemptionOffer(null, 0L, 0L, query.currency(), "NO_ACCOUNT");
        }
        AccountRow row = account.get();

        Instant now = clock.instant();
        Optional<RedemptionPolicyRow> policy = policies.redemptionPolicy(
                query.tenantId(), query.brandId(), now);
        if (policy.isEmpty()) {
            // No policy is not a permissive default. Redemption is enabled for a
            // brand by a deliberate act, and until then the answer is no.
            return new RedemptionOffer(row.id(), row.balanceMinor(), 0L, row.currency(),
                    "REDEMPTION_NOT_ENABLED");
        }
        RedemptionPolicyRow rules = policy.get();

        if (!rules.allowedChannels().isEmpty()
                && !rules.allowedChannels().contains(query.channelCode())) {
            return new RedemptionOffer(row.id(), row.balanceMinor(), 0L, row.currency(),
                    "CHANNEL_NOT_ELIGIBLE");
        }
        if (row.status() != AccountStatus.ACTIVE) {
            return new RedemptionOffer(row.id(), row.balanceMinor(), 0L, row.currency(),
                    "ACCOUNT_NOT_ACTIVE");
        }

        long cap = RedemptionLimit.maximumRedeemable(query.orderTotalMinor(),
                query.deliveryFeeMinor(), rules.maxShareBasisPoints(), rules.minOrderMinor(),
                rules.excludesDeliveryFee());

        // Spendable, not the balance. A lot inside its earn delay is in the
        // balance and is not yet spendable, and offering it here is how a
        // storefront proposes a redemption the checkout then refuses.
        long spendable = store.spendableMinor(query.tenantId(), row.id(), now);

        long maximum = Math.min(cap, spendable);
        String refusal = maximum > 0 ? null
                : (cap == 0 ? "ORDER_NOT_ELIGIBLE" : "INSUFFICIENT_BALANCE");
        return new RedemptionOffer(row.id(), spendable, maximum, row.currency(), refusal);
    }

    @Override
    @Transactional
    public PointsHold reserve(ReserveCommand command) {
        Instant now = clock.instant();

        AccountRow account = store.findAccount(command.tenantId(), command.brandId(),
                        command.customerAccountId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The customer holds no points account at this brand"));

        OrderFacts order = store.orderFacts(command.tenantId(), command.orderId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The order does not exist"));

        // Non-transferability, checked against the order rather than the request.
        if (order.customerAccountId() == null
                || !order.customerAccountId().equals(account.customerAccountId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Points are redeemable only against their own customer's order");
        }
        // The cross-brand rule, at the one place a checkout could cross it.
        if (!order.brandId().equals(account.brandId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Points earned at one brand cannot be spent at another");
        }
        if (!order.currency().equalsIgnoreCase(account.currency())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The order and the points account are in different currencies");
        }

        RedemptionPolicyRow policy = policies
                .redemptionPolicy(command.tenantId(), command.brandId(), now)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Redemption is not enabled for this brand"));

        long cap = RedemptionLimit.maximumRedeemable(order.totalMinor(), order.feeMinor(),
                policy.maxShareBasisPoints(), policy.minOrderMinor(),
                policy.excludesDeliveryFee());
        if (command.amountMinor() <= 0 || command.amountMinor() > cap) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The redemption exceeds what this order permits");
        }

        // The lots, then the debit. The debit is the gate: if it does not match a
        // row, somebody else spent the balance between these two statements and
        // this checkout is refused rather than overdrawing.
        List<LotConsumption> plan;
        try {
            plan = LotConsumption.plan(
                    store.availableLots(command.tenantId(), account.id(), now),
                    command.amountMinor());
        } catch (LotConsumption.InsufficientBalanceException shortfall) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "INSUFFICIENT_BALANCE");
        }

        if (!store.debitBalance(command.tenantId(), account.id(), command.amountMinor(), now)) {
            // The loser of a concurrent checkout. It sees a refusal, never a
            // negative balance, and never somebody else's points.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "INSUFFICIENT_BALANCE");
        }

        UUID reservationId = UUID.randomUUID();
        store.insertReservation(new ReservationRow(reservationId, command.tenantId(), account.id(),
                        command.orderId(), command.tenderId(), command.amountMinor(),
                        ReservationStatus.HELD, now.plus(HOLD_LIFETIME), 1),
                command.idempotencyKey(), now);

        long running = account.balanceMinor();
        for (LotConsumption consumption : plan) {
            if (!store.consumeLot(command.tenantId(), consumption.lotId(),
                    consumption.amountMinor(), now)) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "A lot was consumed by another redemption");
            }
            store.recordReservationLot(reservationId, consumption.lotId(), command.tenantId(),
                    consumption.amountMinor());

            running -= consumption.amountMinor();
            // One entry per lot. A redemption spanning three lots is three
            // movements, because the lot is what expires and a single aggregate
            // debit could not say which expiry the customer just used up.
            //
            // The key is the caller's idempotency key and the lot, and a repeated
            // reserve loses uq_loyalty_reservation_idempotency several statements
            // earlier — so this cannot answer false today. It is required rather
            // than discarded because the debit and the lot consumption above have
            // already happened: if the argument above ever stops holding, the
            // failure has to be this transaction rolling back, not a spent
            // balance with no redemption behind it.
            store.requireEntry(new JdbcLoyaltyStore.NewEntry(UUID.randomUUID(), command.tenantId(),
                    account.id(), EntryType.REDEMPTION, -consumption.amountMinor(), running,
                    consumption.lotId(), command.orderId(), command.tenderId(), null, null,
                    "ORDER_REDEMPTION", command.actor(), null,
                    command.idempotencyKey() + ":" + consumption.lotId(), now), now);
        }

        return new PointsHold(reservationId, account.id(), command.amountMinor(), running,
                plan.size());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Refuses loudly.</strong> This used to read the reservation by
     * existence alone and then attempt a guarded {@code HELD -> SETTLED} update
     * whose {@code false} it ignored — so settling a tender whose hold had
     * already been released matched no row, cleared nothing, threw nothing and
     * logged nothing, and the settlement closed for the full order total with the
     * points back on the customer's balance. A guarded transition whose refusal is
     * discarded is how a money bug becomes silent.
     *
     * <p>Both refusals are conflicts rather than faults, and both name only the
     * status: a reservation's status is not personal data, and the caller needs
     * to know which of the two it hit.
     */
    @Override
    @Transactional
    public void settle(UUID tenantId, UUID tenderId) {
        ReservationRow reservation = requireHold(tenantId, tenderId);
        Instant now = clock.instant();

        // No entry. The points left the balance when the hold was taken, and
        // writing a second debit here would double-count the redemption on every
        // report that sums the ledger.
        if (!store.transitionReservation(tenantId, reservation.id(), ReservationStatus.HELD,
                ReservationStatus.SETTLED, now)) {
            // The hold moved between the read above and this statement — the sweep
            // released it, or a concurrent settlement won. Failing here rolls the
            // caller's transaction back, which is the point: a settlement that
            // claims to be whole while one of its tenders never settled is worse
            // than a handover that refuses.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The points hold for that tender was released or settled concurrently");
        }
        store.clearHold(tenantId, reservation.accountId(), reservation.amountMinor(), now);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Tolerant of a hold that is no longer held, unlike {@link #settle}. A
     * double release is the harmless direction — the points are already back —
     * and it is a real race: the sweep and a failing settlement can reach the same
     * reservation from two transactions.
     */
    @Override
    @Transactional
    public void release(UUID tenantId, UUID tenderId, String reasonCode, String actor) {
        ReservationRow reservation = store.findReservationByTender(tenantId, tenderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No points hold exists for that tender"));
        Instant now = clock.instant();

        if (!store.transitionReservation(tenantId, reservation.id(), ReservationStatus.HELD,
                ReservationStatus.RELEASED, now)) {
            // Somebody else released or settled it first. Returning the points
            // again would credit them twice.
            return;
        }
        // The hold is released along with the points, which is why the second
        // amount is not zero: reserved_minor comes down by what was held.
        returnPoints(tenantId, reservation, reservation.amountMinor(), EntryType.RELEASE,
                reasonCode, actor, now, reservation.amountMinor());
    }

    @Override
    @Transactional
    public void reverse(UUID tenantId, UUID tenderId, long amountMinor, String reasonCode,
            String actor) {
        ReservationRow reservation = store.findReservationByTender(tenantId, tenderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No points were tendered on that tender"));
        if (reservation.status() != ReservationStatus.SETTLED) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "Only a settled points tender can be reversed");
        }

        // A refund never returns more than the tender settled. This is the cap
        // that stops a points-settled order refunding as money at par, and it is
        // checked here — inside the refund transaction — rather than trusted to
        // the caller that computed the refund.
        long alreadyReturned = returnedSoFar(tenantId, tenderId);
        long refundable = reservation.amountMinor() - alreadyReturned;
        if (amountMinor <= 0 || amountMinor > refundable) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A points tender refunds at most what it settled");
        }

        Instant now = clock.instant();
        // Nothing is held any more — the tender settled — so no hold comes down.
        returnPoints(tenantId, reservation, amountMinor, EntryType.REVERSAL, reasonCode, actor,
                now, 0L);

        if (amountMinor == refundable) {
            store.transitionReservation(tenantId, reservation.id(), ReservationStatus.SETTLED,
                    ReservationStatus.REVERSED, now);
        }
    }

    /**
     * A lot the points came back to that cannot keep them, and the key of the
     * entry that returned them, from which the closing entry's key is derived.
     */
    private record ReturnedLot(UUID lotId, String returnKey) {
    }

    /**
     * Puts points back on the lots they came from, at their original expiry, and
     * finishes off the ones that died while they were held.
     *
     * <p>Newest-expiring lot first, which is the mirror of oldest-expiry-first
     * consumption: a partial return gives back the points the customer would have
     * spent last, so the lot nearest to expiring stays spent.
     *
     * <p><strong>A lot can die while its points are held, and both halves of what
     * happens next belong here.</strong> The expiry is not moved — points three
     * days from expiry when spent are three days from expiry when returned, and
     * resetting the clock is a giveaway that compounds on every refund. But the
     * points also cannot simply come back: they land on a lot that is already
     * {@code EXPIRED}, which {@link JdbcLoyaltyStore#availableLots} refuses, so
     * every later redemption is told {@code INSUFFICIENT_BALANCE} for points the
     * customer can see. That was the defect, and it survived every check because
     * {@code balance_minor == SUM(lots.remaining_minor)} stayed true the whole
     * time.
     *
     * <p>So the lot is closed here, in this transaction, the way the sweep would
     * have closed it: the whole of what it holds is destroyed, the balance comes
     * back down, and an {@code EXPIRY} entry says which lot and how much. Not
     * left for the hourly sweep — that would be an hour in which a customer is
     * shown points that checkout refuses — and not destroyed silently, because a
     * balance that changes with no entry is worse than the bug.
     *
     * <p>Order matters and is not incidental. The credit lands before the
     * destruction, so {@code destroyBalance}'s floor is a real test rather than a
     * formality, and the two entries read in the order the movements happened:
     * the points returned, then the points expired.
     *
     * <p>What is destroyed is what the lot <em>holds</em>, not what was returned
     * to it. A lot that lapsed while only part of it was held still carries the
     * unheld part, and that part was going to be destroyed by the next sweep
     * anyway; expiring the lot by halves would leave the remainder to be found
     * later by a query that has no reason to look.
     *
     * <p><strong>A lot can also be destroyed while its points are held, and an
     * account can be closed under them.</strong> That is the same shape one step
     * further on and it used to end differently: {@code restoreLot} relabelled a
     * {@code FORFEITED} lot {@code ACTIVE} and {@code creditBalance} asked
     * nothing about the account, so a cancelled order handed a spendable balance
     * to a customer who had asked to be erased. {@link #forfeitOnReturn} is the
     * answer, and it is deliberately the same answer as expiry: the points come
     * back with their {@code RELEASE} or {@code REVERSAL} entry and go straight
     * out again with a {@code FORFEITURE} naming the lot, because the money moved
     * and the ledger has to say where the value went.
     *
     * @param releasedHold how much of {@code reserved_minor} this return also
     *                     frees — the whole hold on a release, nothing on a
     *                     reversal, whose tender already settled
     */
    private void returnPoints(UUID tenantId, ReservationRow reservation, long amountMinor,
            EntryType entryType, String reasonCode, String actor, Instant now,
            long releasedHold) {

        AccountRow account = store.findAccountById(tenantId, reservation.accountId())
                .orElseThrow(() -> new IllegalStateException(
                        "The hold names an account that does not exist: "
                                + reservation.accountId()));
        boolean closed = account.status() == AccountStatus.CLOSED;

        // Read before anything moves, because the guard at the end of this method
        // is about what this return did and not about what it found.
        long unbackedBefore = store.unbackedValueMinor(tenantId, reservation.accountId());

        List<LotConsumption> taken = store.reservationLots(tenantId, reservation.id());
        long outstanding = amountMinor;
        long running = account.balanceMinor();

        // Which return of this tender this is.
        //
        // Reverse is the only path that can legitimately run more than once
        // against the same (tender, lot) pair, and nothing else in the key said
        // which run it was: two reversals of equal size produced a byte-identical
        // key, appendEntry's ON CONFLICT DO NOTHING answered false, and the
        // caller credited the balance anyway. One movement, one entry — and the
        // counter is per tender, so a genuine retry inside a rolled-back
        // transaction recomputes it from the entries that actually committed.
        int sequence = store.entriesOfTender(tenantId, reservation.tenderId()).size();

        List<ReturnedLot> lapsed = new ArrayList<>();
        List<ReturnedLot> unclaimable = new ArrayList<>();
        long credited = 0L;
        for (int index = taken.size() - 1; index >= 0 && outstanding > 0; index--) {
            LotConsumption consumption = taken.get(index);
            long returned = Math.min(outstanding, consumption.amountMinor());
            JdbcLoyaltyStore.RestoredLot restored =
                    store.restoreLot(tenantId, consumption.lotId(), returned, now);

            running += returned;
            String key = entryType.name() + ":" + reservation.tenderId() + ":"
                    + consumption.lotId() + ":" + returned + ":" + sequence++;
            // The credit below is this entry's other half, and the two are one
            // act. Discarding this answer is what turned a colliding key into a
            // balance the ledger could not explain, so a refusal rolls the whole
            // return back rather than moving value silently. It is the store's
            // rule now rather than this method's, because the same answer was
            // being discarded in eight other places.
            store.requireEntry(new JdbcLoyaltyStore.NewEntry(UUID.randomUUID(), tenantId,
                    reservation.accountId(), entryType, returned, running, consumption.lotId(),
                    reservation.orderId(), reservation.tenderId(), null, null, reasonCode, actor,
                    null, key, now), now);
            credited = Math.addExact(credited, returned);

            // A closed account first, because forfeiture is the stronger fact: on
            // an account whose customer is gone the points are not expiring, they
            // are being destroyed with the rest of the balance.
            if (closed || restored.forfeited()) {
                unclaimable.add(new ReturnedLot(consumption.lotId(), key));
            } else if (restored.lapsed()) {
                lapsed.add(new ReturnedLot(consumption.lotId(), key));
            }
            outstanding -= returned;
        }

        if (outstanding > 0) {
            throw new IllegalStateException(
                    "The hold's lots do not account for the amount being returned");
        }

        // What the entries say, not what the caller asked for. They are equal
        // whenever the loop above completed, and tying the credit to the ledger
        // rather than to the argument is what keeps them equal.
        store.creditBalance(tenantId, reservation.accountId(), credited, releasedHold, now);

        for (ReturnedLot lapse : lapsed) {
            running = Math.subtractExact(running,
                    expireOnReturn(tenantId, reservation, lapse, running, now));
        }
        for (ReturnedLot lost : unclaimable) {
            running = Math.subtractExact(running,
                    forfeitOnReturn(tenantId, reservation, lost, closed, running, now));
        }

        // The invariant the ledger could not state for itself. expireLots guards
        // the converse — a lot holding more than its account's balance — and this
        // is the direction that was missing: value counted in a balance that no
        // lot can ever make spendable.
        //
        // Stated as a delta, not as an absolute. An absolute assertion here threw
        // on value that was already unbacked when the return arrived, which meant
        // a pre-existing inconsistency turned the next cancellation into a failed
        // cancellation — an invariant failing the thing that did not break it. An
        // order that has to end still ends; what cannot happen is this return
        // adding to the problem.
        long unbackedAfter = store.unbackedValueMinor(tenantId, reservation.accountId());
        if (unbackedAfter > unbackedBefore) {
            throw new IllegalStateException(
                    "This return would add " + (unbackedAfter - unbackedBefore)
                            + " to value that can neither be spent nor expired, on account "
                            + reservation.accountId());
        }
        if (unbackedBefore > 0) {
            log.warn("Points account {} already carries {} on lots that can neither be spent nor "
                    + "expired. This return did not add to it and is not the place to refuse; "
                    + "the expiry sweep's repair arm is what clears it.",
                    reservation.accountId(), unbackedBefore);
        }
    }

    /**
     * Closes a lot the points came back to after it had already expired.
     *
     * @return what was destroyed, so the caller's running balance stays the
     *         balance the next entry will claim
     */
    private long expireOnReturn(UUID tenantId, ReservationRow reservation, ReturnedLot lapse,
            long running, Instant now) {

        long remaining = store.findLot(tenantId, lapse.lotId())
                .map(JdbcLoyaltyStore.LotRow::remainingMinor)
                .orElseThrow(() -> new IllegalStateException(
                        "The lot the points were returned to has gone: " + lapse.lotId()));
        if (remaining <= 0) {
            return 0L;
        }
        if (!store.expireLot(tenantId, lapse.lotId(), remaining, now)) {
            throw new IllegalStateException(
                    "The lapsed lot moved inside the returning transaction: " + lapse.lotId());
        }
        if (!store.destroyBalance(tenantId, reservation.accountId(), remaining, now)) {
            throw new IllegalStateException(
                    "A lapsed lot holds more than its account's balance: " + lapse.lotId());
        }
        // Derived from the return key, which requireEntry has just proved was
        // free, so this cannot collide either. Required all the same: the lot has
        // been closed and the balance reduced two statements ago.
        store.requireEntry(new JdbcLoyaltyStore.NewEntry(UUID.randomUUID(), tenantId,
                reservation.accountId(), EntryType.EXPIRY, -remaining,
                Math.subtractExact(running, remaining),
                lapse.lotId(), reservation.orderId(), reservation.tenderId(), null, null,
                // Distinct from the sweep's LOT_EXPIRED so a liability report can
                // tell points that lapsed on the shelf from points that lapsed
                // while an order held them, which is a different operational story.
                "LOT_EXPIRED_ON_RETURN", "loyalty-return", null,
                "EXPIRY:" + lapse.returnKey(), now), now);
        return remaining;
    }

    /**
     * Destroys points that came back to an account or a lot that is finished
     * with.
     *
     * <p>Two shapes, one answer. The lot itself may be {@code FORFEITED} — a
     * closure or an ADR 0029 erasure destroyed it while the tender still held
     * part of it — or the whole account may be {@code CLOSED}, which is what
     * {@code LoyaltyAdjustmentService.forfeit} and {@code merge} both leave
     * behind. Either way the money moved and the customer is gone, so the value
     * can neither be given back nor quietly dropped.
     *
     * <p>So it is returned and then taken away again, in this transaction, with
     * a {@code FORFEITURE} entry naming the lot. Two entries rather than none:
     * the {@code RELEASE} or {@code REVERSAL} says the tender gave the points
     * back, and the {@code FORFEITURE} says the closed account could not keep
     * them. A balance that changes with no entry is worse than the bug, and a
     * balance that does not change while the ledger says it should is the same
     * bug wearing a different hat.
     *
     * <p>The reason code separates the two, because a report that conflates them
     * cannot tell "the customer left mid-order" from "an erasure ran while a
     * tender was outstanding", and the second is an ADR 0029 timing question
     * somebody will want to ask.
     *
     * @return what was destroyed, so the caller's running balance stays the
     *         balance the next entry will claim
     */
    private long forfeitOnReturn(UUID tenantId, ReservationRow reservation, ReturnedLot lost,
            boolean accountClosed, long running, Instant now) {

        long remaining = store.findLot(tenantId, lost.lotId())
                .map(JdbcLoyaltyStore.LotRow::remainingMinor)
                .orElseThrow(() -> new IllegalStateException(
                        "The lot the points were returned to has gone: " + lost.lotId()));
        if (remaining <= 0) {
            return 0L;
        }
        if (!store.forfeitLot(tenantId, lost.lotId(), remaining, now)) {
            throw new IllegalStateException(
                    "The forfeited lot moved inside the returning transaction: " + lost.lotId());
        }
        if (!store.destroyBalance(tenantId, reservation.accountId(), remaining, now)) {
            throw new IllegalStateException(
                    "A forfeited lot holds more than its account's balance: " + lost.lotId());
        }
        // As in expireOnReturn: derived from a key just proved free, and required
        // rather than read because the movement is already behind it.
        store.requireEntry(new JdbcLoyaltyStore.NewEntry(UUID.randomUUID(), tenantId,
                reservation.accountId(), EntryType.FORFEITURE, -remaining,
                Math.subtractExact(running, remaining),
                lost.lotId(), reservation.orderId(), reservation.tenderId(), null, null,
                accountClosed ? "RETURNED_TO_CLOSED_ACCOUNT" : "RETURNED_TO_FORFEITED_LOT",
                "loyalty-return", null, "FORFEITURE:" + lost.returnKey(), now), now);
        return remaining;
    }

    private long returnedSoFar(UUID tenantId, UUID tenderId) {
        return store.entriesOfTender(tenantId, tenderId).stream()
                .filter(entry -> entry.entryType() == EntryType.REVERSAL)
                .mapToLong(EntryRow::amountMinor)
                .sum();
    }

    /**
     * The reservation behind a tender, and it had better still be a hold.
     *
     * <p>The status check is the whole point. Filtering on existence alone let
     * {@link #settle} run against a reservation that had already been released,
     * and everything after that was a no-op nobody could see.
     */
    private ReservationRow requireHold(UUID tenantId, UUID tenderId) {
        ReservationRow reservation = store.findReservationByTender(tenantId, tenderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No points hold exists for that tender"));
        if (reservation.status() != ReservationStatus.HELD) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The points hold for that tender is " + reservation.status()
                            + " and no longer holds anything");
        }
        return reservation;
    }
}
