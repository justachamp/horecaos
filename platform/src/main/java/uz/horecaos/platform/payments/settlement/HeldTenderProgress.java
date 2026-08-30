package uz.horecaos.platform.payments.settlement;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.loyalty.api.HeldTenderPort;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.SettlementRow;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.TenderRow;

/**
 * Answers loyalty's hold sweep: is this tender still going to settle?
 * (ADR 0046).
 *
 * <p>Payments implements it because payments is the only module that can. The
 * question spans a tender's status, which loyalty cannot see, the order's
 * status, which loyalty must not learn to read, and the payment attempt, which
 * neither of the other two owns — and payments already holds all three ends: it
 * owns {@code payments.tenders}, {@code payments.payment_attempts}, and it
 * already consumes ordering's {@link OrderDirectory}.
 *
 * <h2>What "still awaiting" means</h2>
 *
 * <p>The tender must still be reserved. A tender that settled, failed, was
 * released or was reversed has had its hold resolved by whichever path resolved
 * it, and a hold still sitting behind one of those is a leak the sweep should
 * clear rather than preserve.
 *
 * <p>And somebody must still be bringing the money. Two different somebodies,
 * and the order status only names one of them.
 *
 * <ul>
 *   <li>{@code AWAITING_APPROVAL}, {@code CONFIRMED}, {@code PREPARING},
 *       {@code READY}, {@code FULFILLING} — the platform is working on this
 *       order and a cash tender arrives at the door. The hold is renewed for as
 *       long as that is true, which is what stops a forty-minute delivery losing
 *       its points at minute thirty-one.</li>
 *   <li>{@code RECEIVED} and {@code PAYMENT_AUTHORIZING} — nobody here is
 *       working, and the status alone cannot tell an abandoned tab from a
 *       customer part-way through Payme's checkout. <strong>The payment attempt
 *       can.</strong> See below.</li>
 *   <li>every terminal status — the order is over. {@code OrderStateService}
 *       fails the settlement on the way out and releases the hold there; this is
 *       the backstop for an order that ended before that seam existed.</li>
 * </ul>
 *
 * <h2>The fact that separates an abandoned tab from a live redirect</h2>
 *
 * <p>{@code PAYMENT_AUTHORIZING} is where a customer sits for the whole of a
 * provider redirect, and Payme's transaction window is twelve hours
 * ({@code PaymentAttemptService.PAYME_TRANSACTION_TIMEOUT}) — so an order in that
 * status for an hour is an ordinary fact, not an abandoned one. Excluding the
 * status from the renewed set therefore released the hold of a payment that was
 * still perfectly capable of landing, and when it landed the confirmation met a
 * released reservation and rolled back for ever with the money captured.
 *
 * <p>Lengthening the thirty minutes would have been the wrong repair: it trades
 * one wrong constant for another and still cannot tell the two customers apart.
 * The attempt can, because the attempt records what the <em>provider</em> did
 * rather than what the platform is waiting for:
 *
 * <ul>
 *   <li>{@code INITIATED} and {@code PRESENTED} — a payable link was minted and
 *       handed over, and nothing has come back. That is precisely the closed tab
 *       the thirty minutes was always about, and it is released on the old
 *       cadence.</li>
 *   <li>{@code RESERVED}, {@code CAPTURED}, {@code UNCERTAIN} —
 *       {@link PaymentAttemptStatus#blocksFurtherAttempts()}, which the enum
 *       already defines as "holds money, or holds a question about money", and
 *       whose own Javadoc says none of the three may be walked away from. A
 *       reservation means the customer reached the provider and the provider
 *       created a transaction; a capture is money already in; an uncertainty may
 *       still resolve to either. Releasing the points under any of them is
 *       releasing them under a payment that can still land.</li>
 *   <li>every terminal attempt status, or no attempt at all — nothing is coming,
 *       and the hold goes back.</li>
 * </ul>
 *
 * <p>The renewal that buys is bounded rather than open-ended, which is what
 * keeps this from being the long constant by another name. A {@code RESERVED}
 * attempt carries the provider's own {@code expires_at} — Payme's twelve hours
 * measured from {@code params.time}, HorecaOS's reservation timeout on Click — and
 * past it {@link PaymentAttempt#expired(Instant)} answers true, which is the
 * same fact {@code expireStaleReservations} sweeps on. {@code UNCERTAIN} carries
 * a deadline after which it becomes an operations exception, and {@code CAPTURED}
 * is money whose confirmation is the next thing to happen.
 *
 * <h2>Answering "no" is not passive</h2>
 *
 * <p>The sweep releases the hold on the strength of a false answer, inside its
 * own transaction, and this call runs in that transaction. Before the fix that
 * left {@code loyalty.reservations} {@code RELEASED} and {@code payments.tenders}
 * still {@code RESERVED} — a divergence nothing could see and nothing could
 * repair, and the reason a later settlement walked into {@code points.settle}
 * with no hold to settle. So a {@code RESERVED} tender whose answer is no is
 * moved to {@code RELEASED} here, in the same transaction as the release it
 * predicts. The two tables now agree, and
 * {@link CheckoutSettlementPlanner#recordConfirmation} can see the leg is gone
 * instead of discovering it by exception.
 *
 * <p>An order or a tender that cannot be found answers false. A hold pointing at
 * nothing is holding a customer's points for a row that does not exist, and the
 * only safe direction for the customer is to give them back.
 */
@Component
public class HeldTenderProgress implements HeldTenderPort {

    private static final Logger log = LoggerFactory.getLogger(HeldTenderProgress.class);

    /**
     * The order statuses in which somebody is still going to hand the food over.
     *
     * <p>Strings rather than the ordering enum: {@link OrderDirectory} publishes
     * the status as a string precisely so a consumer does not take a dependency
     * on ordering's internal vocabulary, and a status this build does not know is
     * then a status this class treats as "not working", which is the safe answer.
     */
    private static final Set<String> STILL_WORKING = Set.of(
            "AWAITING_APPROVAL", "CONFIRMED", "PREPARING", "READY", "FULFILLING");

    /**
     * The two statuses in which the platform is waiting on a customer rather than
     * working, so the payment attempt decides.
     *
     * <p>Kept apart from the terminal statuses rather than folded into an "else"
     * because a terminal order must never be renewed even if some attempt against
     * it is somehow still open: the order is over, and {@code OrderStateService}
     * has already failed its settlement.
     */
    private static final Set<String> WAITING_ON_THE_CUSTOMER = Set.of(
            "RECEIVED", "PAYMENT_AUTHORIZING");

    private final JdbcSettlementStore store;
    private final OrderDirectory orders;
    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final Clock clock;

    public HeldTenderProgress(JdbcSettlementStore store, OrderDirectory orders,
            JdbcPaymentIntentStore intents, JdbcPaymentAttemptStore attempts, Clock clock) {
        this.store = store;
        this.orders = orders;
        this.intents = intents;
        this.attempts = attempts;
        this.clock = clock;
    }

    // Not readOnly: a false answer records the release it predicts on the tender
    // row, in the sweep's own transaction. See the class Javadoc.
    @Override
    @Transactional
    public boolean stillAwaitingSettlement(UUID tenantId, UUID tenderId) {
        Optional<TenderRow> found = store.findTender(tenantId, tenderId);
        if (found.isEmpty() || found.get().status() != TenderStatus.RESERVED) {
            return false;
        }
        TenderRow tender = found.get();

        if (awaiting(tenantId, tender)) {
            return true;
        }
        // The sweep is about to give these points back. Record it on the tender
        // in the same transaction, so the two tables cannot disagree about
        // whether this leg still holds anything.
        //
        // Balance tenders only. A money tender is RESERVED when its intent has
        // been initiated, and nothing about a points hold gives this class the
        // right to resolve one — the sweep never asks about them, and writing one
        // here on the strength of an answer about points would be inventing a
        // release nobody performed.
        if (tender.settlesFromBalance()) {
            store.transitionTender(tenantId, tenderId, TenderStatus.RESERVED,
                    TenderStatus.RELEASED, clock.instant());
        }
        return false;
    }

    private boolean awaiting(UUID tenantId, TenderRow tender) {
        Optional<SettlementRow> settlement = store.findSettlementById(tenantId,
                tender.settlementId());
        if (settlement.isEmpty()) {
            return false;
        }
        UUID orderId = settlement.get().orderId();
        Optional<String> status = orders.summary(tenantId, orderId)
                .map(OrderDirectory.OrderSummary::status);
        if (status.isEmpty()) {
            return false;
        }
        if (STILL_WORKING.contains(status.get())) {
            return true;
        }
        if (!WAITING_ON_THE_CUSTOMER.contains(status.get())) {
            return false;
        }
        return paymentCanStillLand(tenantId, orderId);
    }

    /**
     * Whether a provider still holds this order's money, or a question about it.
     *
     * <p>Both reads carry the tenant they were authorized against; an intent id
     * and an attempt id are UUIDs that must never be trusted on their own.
     */
    private boolean paymentCanStillLand(UUID tenantId, UUID orderId) {
        Optional<PaymentAttempt> attempt = intents.findLiveForOrder(tenantId, orderId)
                .flatMap(intent -> attempts.findOpenForIntent(tenantId, intent.id()));
        if (attempt.isEmpty() || !attempt.get().blocksFurtherAttempts()) {
            return false;
        }
        Instant now = clock.instant();
        if (attempt.get().status() == PaymentAttemptStatus.RESERVED
                && attempt.get().expired(now)) {
            // The provider's own window has closed. expireStaleReservations is
            // about to cancel it, and a hold renewed past this point would be
            // renewed against nothing.
            return false;
        }
        log.debug("Order {} is not being worked on, but its payment attempt is {}; the points "
                + "hold is renewed rather than released", orderId, attempt.get().status());
        return true;
    }
}
