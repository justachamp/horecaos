package uz.horecaos.platform.loyalty.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.loyalty.domain.AccountStatus;
import uz.horecaos.platform.loyalty.domain.EntryType;
import uz.horecaos.platform.loyalty.domain.LotStatus;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LotRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The movements a person authors, and the two that close an account
 * (ADR 0046, ADR 0027, ADR 0029).
 *
 * <p><strong>An adjustment takes one account and one signed amount, and has no
 * paired form.</strong> That is deliberate and it is the whole treatment of the
 * transfer back door. Two offsetting adjustments are still a transfer with extra
 * steps; making them two separate manual acts, each with a reason code, an
 * actor, and four-eyes approval above the threshold, does not make the manoeuvre
 * impossible. It makes it visible, attributable, and countable on a report,
 * which is the correct treatment for something an operator legitimately does
 * during a support call and illegitimately does as a favour.
 *
 * <p><strong>An unbounded manual credit is a cash drawer any operations console
 * login can open.</strong> Hence the approval threshold, and hence the audit
 * fact written in the same transaction as the entry.
 *
 * <p><strong>Closure and erasure forfeit; they never pay out.</strong> Cashing a
 * balance out at par at exactly the moment nobody is watching is the single
 * thing the not-money constraints exist to prevent. The entries stay, under the
 * financial retention period with the customer reference anonymised, because
 * ADR 0029's closed input keeps financial evidence and drops the identity
 * attached to it.
 */
@Service
public class LoyaltyAdjustmentService {

    /** The ADR 0015 merge reason. Points move between two records of one person. */
    public static final String REASON_ACCOUNT_MERGE = "ACCOUNT_MERGE";

    /** The ADR 0046 rollout reason for an imported legacy balance. */
    public static final String REASON_LEGACY_OPENING_BALANCE = "LEGACY_OPENING_BALANCE";

    /** Written by {@link #clawBack}, which no operator authors. */
    private static final String REASON_ORDER_ACCRUAL_CLAWBACK = "ORDER_ACCRUAL_CLAWBACK";

    /**
     * The reasons that are not an operator's discretion and so do not count
     * towards the aggregate below.
     *
     * <p>A merge is ADR 0015's own evidence-backed workflow, an opening balance is
     * a one-time import, and a clawback is a machine following a refunded order.
     * Counting any of the three would make the next routine goodwill credit on
     * that account need a second pair of eyes for something nobody chose — and an
     * approval queue full of those is an approval queue nobody reads.
     */
    private static final List<String> UNCOUNTED_REASONS =
            List.of(REASON_ACCOUNT_MERGE, REASON_LEGACY_OPENING_BALANCE, REASON_ORDER_ACCRUAL_CLAWBACK);

    /**
     * How far back the aggregate looks.
     *
     * <p>Twenty-four hours, matching {@link ApprovalRequestCommand#DEFAULT_VALIDITY}
     * so the two clocks in this flow agree: an approval is good for a day, and a
     * day's adjustments are what it is weighed against. An hour would be beaten by
     * an operator who waits; a month would eventually put every ordinary 5 000-point
     * apology in front of an approver, which is how a control becomes a rubber
     * stamp.
     */
    private static final Duration AGGREGATE_WINDOW = Duration.ofHours(24);

    /**
     * How many recent entries the window is computed from.
     *
     * <p>Bounded because this is on the request path. An account with more than
     * this many entries in a day is not a support case, and the sum over the newest
     * two hundred is already far past any threshold worth approving.
     */
    private static final int AGGREGATE_SCAN = 200;

    private final JdbcLoyaltyStore store;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;
    private final long approvalThresholdMinor;

    public LoyaltyAdjustmentService(
            JdbcLoyaltyStore store,
            ApprovalService approvals,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.loyalty.adjustment-approval-threshold-minor:100000}") long approvalThresholdMinor) {
        this.store = store;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
        this.approvalThresholdMinor = approvalThresholdMinor;
    }

    /**
     * One operator-authored movement against one account.
     *
     * @param amountMinor signed. Positive credits the customer; negative debits
     *                    them. There is no second account on this command and
     *                    there is no overload that takes one
     */
    public record AdjustmentCommand(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            long amountMinor,
            String currency,
            String reasonCode,
            String reason,
            ActorRef actor,
            String idempotencyKey,
            String correlationId) {}

    /**
     * <p>The threshold is aggregate, not per-adjustment. Ten credits of 20 000 to
     * one account in an afternoon are the 200 000 credit an operator was not
     * allowed to make in one go, and a control that only ever looked at the
     * command in front of it could be walked around by anyone who could count. So
     * the account's own recent adjustments are added to this one, and the sum is
     * what the threshold is applied to.
     *
     * <p>What it does not catch, and deliberately does not pretend to: the same
     * operator spreading credits across many accounts. That is one query away and
     * the query does not exist yet — it needs a sum over
     * {@code loyalty.entries} by actor rather than by account, and an index to
     * support it. The per-account control is the half that closes the manoeuvre
     * this class was written to make visible, which is a gift to one person.
     */
    @Transactional
    public ApprovalOutcome adjust(AdjustmentCommand command) {
        if (command.amountMinor() == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An adjustment moves a non-zero amount");
        }
        Instant now = clock.instant();

        ResourceScope scope = ResourceScope.brand(command.tenantId(), command.brandId());
        long weighed = Math.abs(command.amountMinor()) + recentAdjustmentsMinor(command, now);
        if (weighed >= approvalThresholdMinor) {
            ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                    ApprovalAction.LOYALTY_BALANCE_ADJUST.code(),
                    parametersHash(command),
                    scope,
                    command.actor(),
                    command.reason(),
                    ApprovalRequestCommand.DEFAULT_VALIDITY));
            if (!outcome.mayProceed()) {
                return outcome;
            }
            // Spent here, in this transaction, before the points move. The
            // parameters hash excludes the idempotency key by design, so without
            // this one signature answered every identical resubmission until the
            // approval lapsed and a 500 000-point goodwill credit was as many
            // 500 000-point credits as the maker cared to submit.
            outcome.consume();
            apply(command, scope, approvalIdOf(outcome), now);
            return outcome;
        }

        apply(command, scope, null, now);
        return new ApprovalOutcome.NotRequired();
    }

    private void apply(AdjustmentCommand command, ResourceScope scope, @Nullable UUID approvalId, Instant now) {

        AccountRow account = store.openAccount(
                UUID.randomUUID(),
                command.tenantId(),
                command.brandId(),
                command.customerAccountId(),
                command.currency(),
                now);

        long balanceAfter = account.balanceMinor() + command.amountMinor();
        if (balanceAfter < 0) {
            // The balance floor, restated where an operator would hit it. A
            // clawback larger than what is left is a WRITE_OFF against the
            // tenant, not a negative balance the customer finds later.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An adjustment cannot take a balance below zero");
        }

        UUID entryId = UUID.randomUUID();
        boolean recorded = store.appendEntry(
                new JdbcLoyaltyStore.NewEntry(
                        entryId,
                        command.tenantId(),
                        account.id(),
                        EntryType.ADJUSTMENT,
                        command.amountMinor(),
                        balanceAfter,
                        null,
                        null,
                        null,
                        null,
                        null,
                        command.reasonCode(),
                        command.actor().subject(),
                        approvalId,
                        command.idempotencyKey(),
                        now),
                now);
        if (!recorded) {
            return;
        }

        if (command.amountMinor() > 0) {
            store.creditBalance(command.tenantId(), account.id(), command.amountMinor(), 0L, now);
            // A credited adjustment grants a lot, so the points expire like every
            // other point. A credit with no lot would be a balance that never
            // decays, which is the shape finance finds as a liability line growing
            // against no sale.
            store.insertLot(
                    UUID.randomUUID(),
                    command.tenantId(),
                    account.id(),
                    entryId,
                    command.amountMinor(),
                    now,
                    now.plus(Duration.ofDays(180)),
                    LotStatus.ACTIVE,
                    now);
        } else {
            consumeFromOldestLots(command.tenantId(), account.id(), -command.amountMinor(), now);
            store.destroyBalance(command.tenantId(), account.id(), -command.amountMinor(), now);
        }

        audit.record(new AuditFact(
                UUID.randomUUID(),
                AuditClass.BUSINESS,
                "loyalty.balance.adjust",
                command.actor(),
                scope,
                "loyalty.account",
                account.id(),
                (long) account.version(),
                AuditFact.Outcome.SUCCEEDED,
                command.reason(),
                // No contact point, no name, no order history. ADR 0029 keeps the
                // change document to the movement itself.
                Map.of(
                        "amountMinor",
                        command.amountMinor(),
                        "reasonCode",
                        command.reasonCode(),
                        "balanceAfterMinor",
                        balanceAfter),
                null,
                "loyalty.adjust",
                approvalId,
                command.correlationId(),
                null,
                now));
    }

    /**
     * Takes back a refunded order's accrual, charging to the brand whatever the
     * balance can no longer cover.
     *
     * <p>The case is a refunded order whose accrual has already been spent. The
     * alternatives to absorbing it are both worse: a negative balance the
     * customer discovers on their next order, or a silent write-down nobody can
     * find.
     *
     * <p><strong>The shortfall is not a movement of the customer's points, and
     * writing it as one was a money bug.</strong> It used to be a
     * {@code WRITE_OFF} entry of {@code -2 000} against the account with nothing
     * moving to match it — and there was nothing available to move, because the
     * shortfall is by definition the part the balance could not cover, so the
     * balance is at zero by the time it is reached. V0042 requires
     * {@code balance_minor} to equal {@code SUM(entries.amount_minor)} at all
     * times; that entry made it false by the written-off amount, for ever, and
     * {@code balance == SUM(lots.remaining_minor)} stayed green throughout. The
     * shortfall is a fact about the tenant rather than about the customer, so it
     * is recorded as one, in {@code loyalty.clawbacks}, against the brand whose
     * legal entity absorbs it (V0079).
     *
     * <p><strong>Idempotent by its key, and the key is the gate.</strong> This
     * method's caller is a machine following a refunded order, which is exactly
     * the shape that gets redelivered. The clawback row goes in first: a
     * redelivery loses the unique index on {@code (tenant_id, order_id)} before
     * any balance has moved, and reads its answer back from what the first
     * delivery recorded. Recomputing it would report the whole amount as written
     * off on the second pass, having written off part of it on the first,
     * because the first pass took the balance to zero.
     *
     * <p>Then the entry, and only then the movement. The other order — move,
     * then append, then discard the answer — is what let a redelivered clawback
     * debit the balance a second time for a movement the ledger already held.
     *
     * @return the amount charged to the brand, which is zero when the balance
     *         covered the clawback in full
     */
    @Transactional
    public long clawBack(
            UUID tenantId, UUID brandId, UUID customerAccountId, long amountMinor, UUID orderId, String actor) {
        if (amountMinor <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A clawback takes back a positive amount");
        }
        Instant now = clock.instant();
        AccountRow account = store.findAccount(tenantId, brandId, customerAccountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "The customer holds no points account at this brand"));

        long recoverable = Math.min(amountMinor, account.balanceMinor());
        long shortfall = Math.subtractExact(amountMinor, recoverable);

        // The first write of the transaction, so that a redelivery is refused
        // here rather than after it has moved something.
        if (!store.recordClawback(
                new JdbcLoyaltyStore.ClawbackRow(
                        UUID.randomUUID(),
                        tenantId,
                        brandId,
                        account.id(),
                        orderId,
                        amountMinor,
                        recoverable,
                        shortfall,
                        REASON_ORDER_ACCRUAL_CLAWBACK,
                        actor,
                        now),
                now)) {
            return store.findClawback(tenantId, orderId)
                    .orElseThrow(() ->
                            new IllegalStateException("A clawback of order " + orderId + " is recorded and unreadable"))
                    .writtenOffMinor();
        }

        if (recoverable > 0) {
            // The entry before the movement, and its refusal is not survivable:
            // the gate above has already established that this is the first
            // delivery, so a used key here means a movement recorded under it
            // that this transaction is about to make a second time.
            store.requireEntry(
                    new JdbcLoyaltyStore.NewEntry(
                            UUID.randomUUID(),
                            tenantId,
                            account.id(),
                            EntryType.ADJUSTMENT,
                            -recoverable,
                            Math.subtractExact(account.balanceMinor(), recoverable),
                            null,
                            orderId,
                            null,
                            null,
                            null,
                            REASON_ORDER_ACCRUAL_CLAWBACK,
                            actor,
                            null,
                            "CLAWBACK:" + orderId,
                            now),
                    now);
            consumeFromOldestLots(tenantId, account.id(), recoverable, now);
            if (!store.destroyBalance(tenantId, account.id(), recoverable, now)) {
                // A redemption committed between the read above and this
                // statement and the balance no longer covers what the entry
                // claims. Rolling back is the whole of the repair: the clawback
                // row goes with it and the redelivery starts again from the
                // balance as it now is.
                throw new IllegalStateException("The balance moved inside the clawback of order " + orderId);
            }
        }
        return shortfall;
    }

    /**
     * Closes an account and destroys what is left on it.
     *
     * <p>Used by account closure and by ADR 0029 erasure. One {@code FORFEITURE}
     * per open lot and no payout of any kind: there is no branch in this method
     * that moves money, and there is no other method on this class that could.
     */
    @Transactional
    public long forfeit(UUID tenantId, UUID accountId, String reasonCode, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        AccountRow account = store.findAccountById(tenantId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such points account"));

        long forfeited = 0L;
        long running = account.balanceMinor();
        for (LotRow lot : store.openLots(tenantId, accountId)) {
            long remaining = lot.remainingMinor();
            if (!store.closeLot(tenantId, lot.id(), LotStatus.FORFEITED, now)) {
                continue;
            }
            running -= remaining;
            store.destroyBalance(tenantId, accountId, remaining, now);
            // One FORFEITURE per lot, and the key says so. Nothing can reach this
            // twice for one lot — closeLot is a guarded transition out of PENDING
            // or ACTIVE and openLots answers on those two statuses — so a used
            // key here would mean the lot was closed twice, which the balance has
            // already been reduced for. requireEntry rather than a discarded
            // boolean, because that argument is about today's callers and the
            // destroyBalance above is unconditional.
            store.requireEntry(
                    new JdbcLoyaltyStore.NewEntry(
                            UUID.randomUUID(),
                            tenantId,
                            accountId,
                            EntryType.FORFEITURE,
                            -remaining,
                            running,
                            lot.id(),
                            null,
                            null,
                            null,
                            null,
                            reasonCode,
                            actor.subject(),
                            null,
                            "FORFEITURE:" + lot.id(),
                            now),
                    now);
            forfeited += remaining;
        }

        store.setAccountStatus(tenantId, accountId, AccountStatus.CLOSED, now);

        audit.record(new AuditFact(
                UUID.randomUUID(),
                AuditClass.BUSINESS,
                "loyalty.balance.forfeit",
                actor,
                ResourceScope.brand(tenantId, account.brandId()),
                "loyalty.account",
                accountId,
                (long) account.version(),
                AuditFact.Outcome.SUCCEEDED,
                "Account closed under " + reasonCode,
                Map.of("forfeitedMinor", forfeited, "reasonCode", reasonCode),
                null,
                "loyalty.adjust",
                null,
                correlationId,
                null,
                now));
        return forfeited;
    }

    /**
     * Moves points between two records of the same person (ADR 0015).
     *
     * <p>The one operation that genuinely moves points between accounts, and it
     * is a merge rather than a transfer: an evidence-backed workflow over two
     * records of one human being. It is written as an {@code ADJUSTMENT} pair
     * with reason {@code ACCOUNT_MERGE} — the same command shape as any other
     * adjustment, twice, so there is still no paired form to reuse for a gift.
     *
     * <p>Both accounts must be at the same brand. Under {@code BRAND_ISOLATED} a
     * merge cannot cross a brand partition, so neither can the points; under
     * {@code TENANT_SHARED} the brand's liability still belongs to the brand's
     * legal entity.
     *
     * <p>A lot whose expiry has already passed is expired here rather than
     * carried across. {@code openLots} answers on status, and a lot stays
     * {@code ACTIVE} until the hourly sweep reaches it, so a merge run in that
     * window used to open a lot on the target that was {@code EXPIRED} on the day
     * it was created and still held value — the target's balance would carry
     * points that no redemption could ever reach.
     */
    @Transactional
    public long merge(UUID tenantId, UUID sourceAccountId, UUID targetAccountId, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        AccountRow source = store.findAccountById(tenantId, sourceAccountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such points account"));
        AccountRow target = store.findAccountById(tenantId, targetAccountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such points account"));

        if (!source.brandId().equals(target.brandId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A merge cannot move points between brands");
        }

        long moved = 0L;
        long sourceRunning = source.balanceMinor();
        long targetRunning = target.balanceMinor();

        // Lots are preserved at their original expires_at. A merge that reset the
        // clock would be a way of refreshing an expiring balance by asking support
        // to merge an account with itself's duplicate.
        for (LotRow lot : store.openLots(tenantId, sourceAccountId)) {
            long remaining = lot.remainingMinor();

            if (!lot.expiresAt().isAfter(now)) {
                // The lot's life ran out before the merge reached it — the hourly
                // sweep had simply not got there yet. Carrying it across would
                // credit the target's balance and open a lot already EXPIRED with
                // value on it: points the customer can see on their surviving
                // record and can never spend. It is expired on the source
                // instead, under the sweep's own reason code.
                //
                // What stops this and the sweep both expiring the lot is
                // expireLot, whose WHERE clause carries the remaining the caller
                // read: whichever transaction gets there second matches nothing
                // and skips before it moves anything. It is not the shared
                // idempotency key, which the two used to have — that only made a
                // second movement silent instead of impossible.
                if (!store.expireLot(tenantId, lot.id(), remaining, now)) {
                    continue;
                }
                sourceRunning -= remaining;
                if (!store.destroyBalance(tenantId, sourceAccountId, remaining, now)) {
                    throw new IllegalStateException("A lot holds more than its account's balance: " + lot.id());
                }
                store.requireEntry(
                        new JdbcLoyaltyStore.NewEntry(
                                UUID.randomUUID(),
                                tenantId,
                                sourceAccountId,
                                EntryType.EXPIRY,
                                -remaining,
                                sourceRunning,
                                lot.id(),
                                null,
                                null,
                                null,
                                null,
                                "LOT_EXPIRED",
                                actor.subject(),
                                null,
                                LedgerKeys.expiry(lot.id(), now),
                                now),
                        now);
                continue;
            }

            if (!store.closeLot(tenantId, lot.id(), LotStatus.CONSUMED, now)) {
                continue;
            }
            sourceRunning -= remaining;
            store.destroyBalance(tenantId, sourceAccountId, remaining, now);
            // Both halves keyed on the lot, which closeLot has just moved out of
            // PENDING or ACTIVE for good, so one lot can be merged across once.
            // Neither answer may be discarded even so: the debit above and the
            // credit below both happen whatever the ledger says, and a merge that
            // moved points between two accounts with one of its two entries
            // missing is a balance nobody could reconcile on either side.
            UUID debit = UUID.randomUUID();
            store.requireEntry(
                    new JdbcLoyaltyStore.NewEntry(
                            debit,
                            tenantId,
                            sourceAccountId,
                            EntryType.ADJUSTMENT,
                            -remaining,
                            sourceRunning,
                            lot.id(),
                            null,
                            null,
                            null,
                            null,
                            REASON_ACCOUNT_MERGE,
                            actor.subject(),
                            null,
                            "MERGE_OUT:" + lot.id(),
                            now),
                    now);

            targetRunning += remaining;
            UUID credit = UUID.randomUUID();
            store.requireEntry(
                    new JdbcLoyaltyStore.NewEntry(
                            credit,
                            tenantId,
                            targetAccountId,
                            EntryType.ADJUSTMENT,
                            remaining,
                            targetRunning,
                            null,
                            null,
                            null,
                            null,
                            null,
                            REASON_ACCOUNT_MERGE,
                            actor.subject(),
                            null,
                            "MERGE_IN:" + lot.id(),
                            now),
                    now);
            store.creditBalance(tenantId, targetAccountId, remaining, 0L, now);
            // Only PENDING or ACTIVE reaches here: an already-expired lot was
            // expired on the source above rather than opened again on the target.
            store.insertLot(
                    UUID.randomUUID(),
                    tenantId,
                    targetAccountId,
                    credit,
                    remaining,
                    lot.earnsAt(),
                    lot.expiresAt(),
                    lot.earnsAt().isAfter(now) ? LotStatus.PENDING : LotStatus.ACTIVE,
                    now);
            moved += remaining;
        }

        store.setAccountStatus(tenantId, sourceAccountId, AccountStatus.CLOSED, now);

        audit.record(new AuditFact(
                UUID.randomUUID(),
                AuditClass.BUSINESS,
                "loyalty.account.merge",
                actor,
                ResourceScope.brand(tenantId, source.brandId()),
                "loyalty.account",
                targetAccountId,
                (long) target.version(),
                AuditFact.Outcome.SUCCEEDED,
                "ADR 0015 account merge",
                Map.of("movedMinor", moved, "sourceAccountId", sourceAccountId.toString()),
                null,
                "loyalty.adjust",
                null,
                correlationId,
                null,
                now));
        return moved;
    }

    /** Takes value off the oldest-expiring lots, which is where a debit comes from. */
    private void consumeFromOldestLots(UUID tenantId, UUID accountId, long amountMinor, Instant now) {
        long outstanding = amountMinor;
        List<LotRow> lots = store.openLots(tenantId, accountId);
        for (LotRow lot : lots) {
            if (outstanding == 0) {
                break;
            }
            long taken = Math.min(outstanding, lot.remainingMinor());
            if (taken <= 0) {
                continue;
            }
            store.consumeLot(tenantId, lot.id(), taken, now);
            outstanding -= taken;
        }
        if (outstanding > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The account's lots do not hold that many points");
        }
    }

    /**
     * What this account has already been adjusted by, inside the window.
     *
     * <p>Absolute values, summed. Two offsetting adjustments net to nothing and
     * are still a transfer with extra steps, so netting them out here would hand
     * the manoeuvre back the cover this class exists to remove.
     *
     * <p>A retried command with the same idempotency key sees its own earlier
     * entry and may therefore cross the threshold on the second attempt. The
     * movement is not applied twice — the entry's idempotency key refuses it — so
     * the cost is an approval request for something already recorded, which an
     * approver can decline. That is the right way round: the failure mode of a
     * retry is one spurious request, and the failure mode of ignoring the entry
     * would be a way to replay past the control.
     */
    private long recentAdjustmentsMinor(AdjustmentCommand command, Instant now) {
        Instant since = now.minus(AGGREGATE_WINDOW);
        return store.findAccount(command.tenantId(), command.brandId(), command.customerAccountId())
                .map(account -> store.entries(command.tenantId(), account.id(), AGGREGATE_SCAN).stream()
                        .filter(entry -> entry.entryType() == EntryType.ADJUSTMENT)
                        .filter(entry -> !UNCOUNTED_REASONS.contains(entry.reasonCode()))
                        .filter(entry -> entry.occurredAt().isAfter(since))
                        .mapToLong(entry -> Math.abs(entry.amountMinor()))
                        .sum())
                .orElse(0L);
    }

    private static @Nullable UUID approvalIdOf(ApprovalOutcome outcome) {
        return outcome instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;
    }

    /**
     * Binds an approval to the exact movement it approved.
     *
     * <p>Account, amount and reason. An approval for 50 000 must not be reusable
     * for 500 000, which is the failure a hash over fewer fields permits.
     */
    private static String parametersHash(AdjustmentCommand command) {
        String material = command.tenantId() + "|" + command.brandId() + "|"
                + command.customerAccountId() + "|" + command.amountMinor() + "|"
                + command.currency() + "|" + command.reasonCode();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
