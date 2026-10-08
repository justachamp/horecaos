package uz.horecaos.platform.kitchen.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.kitchen.application.port.KitchenPaymentSource;
import uz.horecaos.platform.kitchen.application.port.KitchenPaymentSource.OrderState;
import uz.horecaos.platform.kitchen.domain.KitchenPaidHoldPolicy.Mode;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.domain.TicketStatus;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.horecaos.platform.ordering.api.OrderStatusFacts;
import uz.horecaos.platform.ordering.api.PaymentStatusFacts;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The paid-only hold in the kitchen buffer (ADR 0168).
 *
 * <p>A ticket waiting in the buffer is held for time and for a person; this adds money. The gating fact is
 * the pair ADR 0013 already defines and {@code ordering.api.PaymentStatusFacts} says once: the order's
 * payment is <em>required</em> before the kitchen and has <em>not arrived</em> ({@code PENDING} or {@code
 * FAILED}); {@code AUTHORIZED} and {@code CAPTURED} release it; {@code NOT_REQUIRED} never holds, which
 * is what keeps a cash order, a dine-in tab and a weighed order paid after the weighing (ADR 0153) from
 * ever being held.
 *
 * <p><strong>Placed, never at ticket open.</strong> At {@code OrderConfirmed} a legitimately unpaid online
 * order does not exist, and a just-captured one can be seen as {@code PENDING} by a before-commit listener
 * that runs before the projection is written; a hold decided there would delay every paid order by a tick.
 * It is placed in two level-triggered reads after the order's own state is written: the amendment that made
 * payment required ({@link #reconcileOrder}, the one path to a live unpaid online order) and a sweep in the
 * release worker's own tick ({@link #sweep}), so a path nobody anticipated costs one tick.
 *
 * <p><strong>The policy decides whether anything is held.</strong> {@code SHADOW}, the built-in default,
 * evaluates every ticket exactly as {@code HOLD_UNTIL_PAID} would and counts what it would have held;
 * a ticket is never held in it. {@code OFF} neither holds nor counts. Any mode other than {@code
 * HOLD_UNTIL_PAID} clears the holds the sweep finds, which is the kill switch.
 *
 * <p><strong>A fired ticket is never recalled.</strong> The hold applies to {@code HELD} tickets only.
 */
@Service
public class KitchenPaymentHoldService {

    private static final Logger log = LoggerFactory.getLogger(KitchenPaymentHoldService.class);

    /** The closed registry of reasons a person may fire a payment-held ticket anyway; no free text (ADR 0168). */
    public static final Set<String> OVERRIDE_REASONS =
            Set.of("PAYMENT_PROMISED_AT_COUNTER", "CUSTOMER_KNOWN_TO_BRANCH", "MANAGER_DECISION");

    private static final String OVERRIDE_AUDIT_ACTION = "kitchen.ticket.payment_hold.override";
    private static final String ACTOR = "kitchen-payment-hold";

    private final JdbcKitchenStore kitchen;
    private final KitchenPaymentSource payments;
    private final KitchenPaidHoldPolicies policies;
    private final KitchenTicketService tickets;
    private final AuditRecorder audit;
    private final Clock clock;
    private final MeterRegistry meters;

    private final AtomicLong heldGauge = new AtomicLong();
    private final AtomicLong shadowGauge = new AtomicLong();

    public KitchenPaymentHoldService(
            JdbcKitchenStore kitchen,
            KitchenPaymentSource payments,
            KitchenPaidHoldPolicies policies,
            KitchenTicketService tickets,
            AuditRecorder audit,
            Clock clock,
            MeterRegistry meters) {
        this.kitchen = kitchen;
        this.payments = payments;
        this.policies = policies;
        this.tickets = tickets;
        this.audit = audit;
        this.clock = clock;
        this.meters = meters;
        // Per deployment, never per tenant (ADR 0168 observability): a gauge of the tickets held for
        // payment, and of those a SHADOW tick would hold.
        meters.gauge("kitchen.payment_hold.held", heldGauge);
        meters.gauge("kitchen.payment_hold.shadow_would_hold", shadowGauge);
    }

    // ----------------------------------------------------------------- the amendment path

    /**
     * Reconciles one order's buffered ticket against the payment state the same transaction has just
     * written. Called by {@link KitchenAmendmentListener} for every applied amendment, before commit: the
     * one place a payment becomes required after the order was confirmed.
     */
    @Transactional
    public void reconcileOrder(UUID tenantId, UUID orderId) {
        Optional<TicketRow> ticket = kitchen.findTicketByOrder(tenantId, orderId);
        if (ticket.isEmpty() || ticket.get().status() != TicketStatus.HELD) {
            return;
        }
        OrderState state = payments.orderState(tenantId, List.of(orderId)).get(orderId);
        if (state == null) {
            return;
        }
        reconcile(ticket.get(), state, clock.instant(), true);
    }

    /**
     * The capture event is the proof the money arrived (ADR 0168 Decision 4a): no read of the projection is
     * involved. Clears the hold the event pays for; the ticket returns to its own schedule.
     */
    @Transactional
    public void onPaymentCaptured(UUID tenantId, UUID orderId) {
        kitchen.findTicketByOrder(tenantId, orderId)
                .filter(ticket -> ticket.status() == TicketStatus.HELD && ticket.heldForPayment())
                .ifPresent(ticket -> clear(ticket, "cleared_paid", clock.instant()));
    }

    // ----------------------------------------------------------------- the sweep

    /**
     * One tick of the sweep, in {@link KitchenReleaseWorker}'s own interval.
     *
     * <p>Two reads. First, the buffered tickets that carry no hold and whose order's payment is required
     * and absent: held under {@code HOLD_UNTIL_PAID}, counted under {@code SHADOW}. Second, the tickets
     * already held: cleared when the money arrived or the policy no longer holds, and otherwise checked
     * against their schedule, once per hold, for the blocked and the overdue notice.
     *
     * @return how many holds were placed or cleared this tick
     */
    @Transactional
    public int sweep(int batchSize) {
        Instant now = clock.instant();
        int changed = 0;
        long wouldHold = 0;

        for (KitchenPaymentSource.UnpaidBufferedTicket candidate : payments.unpaidBufferedTickets(batchSize)) {
            Optional<TicketRow> ticket = kitchen.findTicket(candidate.tenantId(), candidate.ticketId());
            if (ticket.isEmpty()) {
                continue;
            }
            Outcome outcome = reconcile(
                    ticket.get(), new OrderState(candidate.orderStatus(), candidate.paymentStatus()), now, false);
            if (outcome == Outcome.WOULD_HOLD) {
                wouldHold++;
            } else if (outcome != Outcome.NONE) {
                changed++;
            }
        }

        Map<UUID, Map<UUID, OrderState>> statesByTenant = new HashMap<>();
        List<TicketRow> held = kitchen.findPaymentHeld(batchSize);
        Map<UUID, List<UUID>> orderIdsByTenant = new HashMap<>();
        for (TicketRow ticket : held) {
            orderIdsByTenant
                    .computeIfAbsent(ticket.tenantId(), tenant -> new ArrayList<>())
                    .add(ticket.orderId());
        }
        orderIdsByTenant.forEach(
                (tenantId, orderIds) -> statesByTenant.put(tenantId, payments.orderState(tenantId, orderIds)));
        for (TicketRow ticket : held) {
            OrderState state =
                    statesByTenant.getOrDefault(ticket.tenantId(), Map.of()).get(ticket.orderId());
            if (state == null) {
                continue;
            }
            Outcome outcome = reconcile(ticket, state, now, false);
            if (outcome != Outcome.NONE && outcome != Outcome.WOULD_HOLD) {
                changed++;
            }
        }

        heldGauge.set(kitchen.countPaymentHeld());
        shadowGauge.set(wouldHold);
        if (log.isDebugEnabled() && (changed > 0 || wouldHold > 0)) {
            log.debug("Payment-hold sweep: {} changed, {} would be held under SHADOW", changed, wouldHold);
        }
        return changed;
    }

    // ----------------------------------------------------------------- the decision

    private enum Outcome {
        NONE,
        PLACED,
        CLEARED,
        VOIDED,
        NOTIFIED,
        WOULD_HOLD
    }

    /**
     * The one decision, level-triggered: given the ticket as it is and the payment state as it is, what
     * should be true. Every caller (the amendment, the capture, the sweep) asks it and none of them
     * remembers what the others did.
     */
    private Outcome reconcile(TicketRow ticket, OrderState state, Instant now, boolean fromAmendment) {
        if (ticket.status() != TicketStatus.HELD) {
            // A fired ticket is never recalled and never carries a hold (ADR 0168 Decision 9).
            return Outcome.NONE;
        }
        if (OrderStatusFacts.isTerminal(state.status())) {
            // The listener should have voided it; a missed event costs one tick.
            return tickets.voidBufferedTicket(ticket, now).isPresent() ? Outcome.VOIDED : Outcome.NONE;
        }

        KitchenPaidHoldPoliciesView policy = policyAt(ticket);
        boolean held = ticket.heldForPayment();
        boolean absent = PaymentStatusFacts.moneyRequiredAndAbsent(state.paymentStatus());

        if (policy.mode == Mode.OFF) {
            // Neither holds nor counts; and any hold the sweep finds is cleared.
            return held ? clear(ticket, "cleared_policy", now) : Outcome.NONE;
        }
        if (policy.mode == Mode.SHADOW) {
            if (held) {
                return clear(ticket, "cleared_policy", now);
            }
            if (absent) {
                if (fromAmendment) {
                    // Once for the amendment; the sweep sees the same ticket every tick and only gauges it.
                    tickets.countHold("shadow_would_hold");
                    log.info(
                            "Under SHADOW the paid hold would have held ticket {} of order {}",
                            ticket.id(),
                            ticket.orderId());
                }
                return Outcome.WOULD_HOLD;
            }
            return Outcome.NONE;
        }

        // HOLD_UNTIL_PAID
        if (!held) {
            return absent ? place(ticket, policy, now) : Outcome.NONE;
        }
        if (!absent) {
            // The money arrived (AUTHORIZED, CAPTURED), or it is no longer owed (NOT_REQUIRED, VOIDED,
            // REFUNDED): either way the reason for holding is gone.
            return clear(ticket, "cleared_paid", now);
        }
        return announce(ticket, policy, now);
    }

    private Outcome place(TicketRow ticket, KitchenPaidHoldPoliciesView policy, Instant now) {
        Optional<Integer> placed =
                kitchen.placePaymentHold(ticket.tenantId(), ticket.id(), policy.policyId, policy.policyVersion, now);
        if (placed.isEmpty()) {
            return Outcome.NONE;
        }
        kitchen.recordEvent(
                ticket.tenantId(),
                ticket.id(),
                null,
                TicketStatus.HELD.name(),
                TicketStatus.HELD.name(),
                "PAYMENT_HOLD_PLACED",
                "SYSTEM_JOB",
                ACTOR,
                null,
                ticket.orderId().toString(),
                now);
        tickets.countHold("placed");
        tickets.signalBoardChanged(ticket.tenantId(), ticket.locationId(), ticket.id(), placed.get(), now);
        return Outcome.PLACED;
    }

    /**
     * Ends the hold and returns the ticket to its own schedule. It does not fire the ticket by itself,
     * with two exceptions that are the schedule: an {@code AUTO_ON_CONFIRM} ticket and a {@code SCHEDULED}
     * ticket whose {@code release_at} has already passed fire at once, through the conditional update every
     * release goes through. A {@code SCHEDULED} ticket not yet due waits for its time; a {@code MANUAL_HOLD}
     * ticket stays held for the person who held it.
     */
    private Outcome clear(TicketRow ticket, String outcome, Instant now) {
        Optional<Integer> cleared = kitchen.clearPaymentHold(ticket.tenantId(), ticket.id(), now);
        if (cleared.isEmpty()) {
            return Outcome.NONE;
        }
        kitchen.recordEvent(
                ticket.tenantId(),
                ticket.id(),
                null,
                TicketStatus.HELD.name(),
                TicketStatus.HELD.name(),
                "PAYMENT_HOLD_CLEARED",
                "SYSTEM_JOB",
                ACTOR,
                null,
                ticket.orderId().toString(),
                now);
        tickets.countHold(outcome);
        boolean dueNow = ticket.releaseMode() == ReleaseMode.AUTO_ON_CONFIRM
                || (ticket.releaseMode() == ReleaseMode.SCHEDULED
                        && ticket.releaseAt() != null
                        && !ticket.releaseAt().isAfter(now));
        if (dueNow) {
            tickets.fire(
                    ticket.tenantId(),
                    ticket.id(),
                    "RELEASE_SCHEDULED",
                    "SYSTEM_JOB",
                    ACTOR,
                    null,
                    ticket.orderId().toString(),
                    now);
        } else {
            tickets.signalBoardChanged(ticket.tenantId(), ticket.locationId(), ticket.id(), cleared.get(), now);
        }
        return Outcome.CLEARED;
    }

    /**
     * A held ticket whose money is still absent: when {@code release_at} arrives it records {@code
     * PAYMENT_HOLD_BLOCKED_RELEASE} once and raises the board signal; {@code overdueAfterMinutes} later it
     * records {@code PAYMENT_HOLD_OVERDUE} once. Nothing is cancelled and nothing is fired: the exits are the
     * customer paying, an operator cancelling the order, or the override.
     */
    private Outcome announce(TicketRow ticket, KitchenPaidHoldPoliciesView policy, Instant now) {
        if (ticket.releaseAt() == null || ticket.releaseAt().isAfter(now)) {
            return Outcome.NONE;
        }
        Outcome outcome = Outcome.NONE;
        if (ticket.holdBlockedNotifiedAt() == null
                && kitchen.markHoldBlockedNotified(ticket.tenantId(), ticket.id(), now)) {
            kitchen.recordEvent(
                    ticket.tenantId(),
                    ticket.id(),
                    null,
                    TicketStatus.HELD.name(),
                    TicketStatus.HELD.name(),
                    "PAYMENT_HOLD_BLOCKED_RELEASE",
                    "SYSTEM_JOB",
                    ACTOR,
                    null,
                    ticket.orderId().toString(),
                    now);
            tickets.countHold("blocked_release");
            tickets.signalBoardChanged(ticket.tenantId(), ticket.locationId(), ticket.id(), ticket.version() + 1, now);
            outcome = Outcome.NOTIFIED;
        }
        Instant overdueAt = ticket.releaseAt().plus(Duration.ofMinutes(policy.overdueAfterMinutes));
        if (ticket.holdOverdueNotifiedAt() == null
                && !overdueAt.isAfter(now)
                && kitchen.markHoldOverdueNotified(ticket.tenantId(), ticket.id(), now)) {
            kitchen.recordEvent(
                    ticket.tenantId(),
                    ticket.id(),
                    null,
                    TicketStatus.HELD.name(),
                    TicketStatus.HELD.name(),
                    "PAYMENT_HOLD_OVERDUE",
                    "SYSTEM_JOB",
                    ACTOR,
                    null,
                    ticket.orderId().toString(),
                    now);
            tickets.countHold("overdue");
            tickets.signalBoardChanged(ticket.tenantId(), ticket.locationId(), ticket.id(), ticket.version() + 2, now);
            outcome = Outcome.NOTIFIED;
        }
        return outcome;
    }

    private KitchenPaidHoldPoliciesView policyAt(TicketRow ticket) {
        KitchenPaidHoldPolicies.Resolved resolved =
                policies.resolveFor(ticket.tenantId(), ticket.brandId(), ticket.locationId());
        return new KitchenPaidHoldPoliciesView(
                resolved.mode(),
                resolved.document().overdueAfterMinutes(),
                resolved.identity().map(found -> found.policyId()).orElse(null),
                resolved.identity().map(found -> found.policyVersion()).orElse(null));
    }

    private record KitchenPaidHoldPoliciesView(
            Mode mode,
            int overdueAfterMinutes,
            @Nullable UUID policyId,
            @Nullable Integer policyVersion) {}

    // ----------------------------------------------------------------- the override

    /**
     * {@code POST .../release} on a ticket that may be held for payment (ADR 0168 Decision 6).
     *
     * <p>A ticket that is not held is released exactly as before. A payment-held one answers {@code 409
     * PAYMENT_HOLD} unless the caller holds {@code kitchen.ticket.release.override} <em>and</em> sends a
     * reason code from the closed registry; with both it fires, records {@code PAYMENT_HOLD_OVERRIDDEN} on the
     * ticket and an ADR 0027 {@code BUSINESS} fact naming the person, the code and the order. The override does
     * not touch the payment: the order still shows unpaid.
     */
    @Transactional
    public TicketRow release(
            UUID tenantId,
            UUID ticketId,
            int expectedVersion,
            String reasonCode,
            boolean overrideGranted,
            @Nullable String overrideReasonCode,
            String actorId,
            @Nullable String correlationId) {

        TicketRow ticket = tickets.require(tenantId, ticketId);
        if (ticket.status() != TicketStatus.HELD || !ticket.heldForPayment()) {
            return tickets.releaseNow(tenantId, ticketId, expectedVersion, reasonCode, actorId, correlationId);
        }
        if (!overrideGranted || overrideReasonCode == null || !OVERRIDE_REASONS.contains(overrideReasonCode)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("holdReason", "PAYMENT");
            details.put(
                    "overrideReasonCodes", OVERRIDE_REASONS.stream().sorted().toList());
            throw new ApiException(
                    ErrorCode.PAYMENT_HOLD,
                    "This ticket is held until its order is paid. Firing it needs kitchen.ticket.release.override "
                            + "and one of the registered reason codes.",
                    details);
        }
        if (ticket.version() != expectedVersion) {
            throw new ApiException(ErrorCode.STALE_VERSION, "The ticket changed since it was read");
        }

        Instant now = clock.instant();
        Optional<TicketRow> fired = tickets.fire(
                tenantId, ticketId, "RELEASE_COMMAND", "USER", actorId, overrideReasonCode, correlationId, now);
        if (fired.isEmpty()) {
            return tickets.require(tenantId, ticketId);
        }
        kitchen.recordEvent(
                tenantId,
                ticketId,
                null,
                TicketStatus.HELD.name(),
                TicketStatus.FIRED.name(),
                "PAYMENT_HOLD_OVERRIDDEN",
                "USER",
                actorId,
                overrideReasonCode,
                correlationId,
                now);
        audit.record(AuditFact.of(OVERRIDE_AUDIT_ACTION, AuditClass.BUSINESS)
                .by(ActorRef.user(actorId, null))
                .at(ResourceScope.location(tenantId, ticket.brandId(), ticket.locationId()))
                .target("kitchen.ticket", ticketId)
                .because(overrideReasonCode)
                .changed(ChangeDocuments.diff(
                        Map.of(
                                "holdReason",
                                "PAYMENT",
                                "orderId",
                                ticket.orderId().toString()),
                        Map.of(
                                "holdReason",
                                "OVERRIDDEN",
                                "orderId",
                                ticket.orderId().toString())))
                .correlatedBy(ticket.orderId().toString())
                .occurredAt(now)
                .build());
        tickets.countHold("overridden");
        return fired.get();
    }
}
