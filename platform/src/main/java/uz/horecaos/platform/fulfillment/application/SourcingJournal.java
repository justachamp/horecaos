package uz.horecaos.platform.fulfillment.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.domain.sourcing.AttemptStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;

/**
 * The durable half of one sourcing tick (ADR 0014).
 *
 * <p>Every method here exists because an effect on the other side of it is
 * externally visible: a courier's phone rings, a partner dispatches somebody and
 * bills for it. At-least-once scheduling plus a non-idempotent effect books two
 * couriers for one order, so the rule this interface enforces is that
 * <em>nothing is asked of anybody until the row that says we asked is committed,
 * and that row is keyed on something a replay reproduces</em>.
 *
 * <p>The single-winner invariant is not in this interface. It is
 * {@code ux_attempt_one_accepted}, {@code ux_attempt_one_offered} and
 * {@code ux_shipment_one_active_per_plan} in V0054, and every method below either
 * succeeds against them or reports that it lost — none of them counts first. ADR
 * 0014 rejects a service-side count by name, because counting races two
 * dispatchers into two couriers.
 */
public interface SourcingJournal {

    /**
     * What this plan has already tried, rebuilt from the attempt rows themselves.
     *
     * <p>Rebuilt rather than read from the job's checkpoint. The checkpoint is a
     * convenience for an operator reading the job row; the attempts are the
     * evidence, and two places holding one truth is how a restart re-offers an
     * order to a courier who already declined it.
     *
     * @param startedAt when this plan's sourcing began — the job's creation, not
     *                  this tick. The fleet budget is measured from it, so a
     *                  scheduler that wakes late does not hand the fleet extra time
     */
    SourcingProgress progress(UUID tenantId, UUID planId, Instant startedAt);

    /**
     * The attempt row that must exist before a partner is called.
     *
     * <p>Idempotent on {@code (tenant_id, idempotency_key)}, which is the same key
     * the partner sees. A replayed tick therefore finds the attempt it wrote last
     * time — with whatever the partner already answered on it — instead of
     * creating a second booking under a fresh id.
     */
    OpenAttempt openPartnerAttempt(PartnerAttempt attempt);

    /**
     * Turns a receipt into a shipment and a winner, or into a recorded failure.
     *
     * @return true when this call is the one that produced the plan's single
     *         active shipment. False means it lost the compare-and-set or the
     *         receipt was not a booking; either way the caller must not book
     *         anything else for this plan on the strength of it
     */
    boolean settlePartnerAttempt(UUID tenantId, UUID attemptId, BookingReceipt receipt,
            Instant now);

    /**
     * The durable offer a courier can accept against.
     *
     * <p>Written before the courier is told, for the same reason a partner attempt
     * is: an offer nothing can hold a courier to is one two dispatch ticks can
     * make twice. {@code ux_attempt_one_offered} is what makes the second write
     * lose rather than produce a second live offer.
     */
    OpenAttempt openInternalOffer(InternalOffer offer);

    /**
     * A courier taking an offer. The compare-and-set, in one statement.
     *
     * @return true when this courier is the plan's winner. False is the ordinary
     *         answer for the second of two taps and for an offer that lapsed
     *         first, and it must be rendered to the courier as "somebody else took
     *         it" rather than as an error
     */
    boolean acceptOffer(UUID tenantId, UUID attemptId, UUID courierId, Instant now);

    /**
     * The plan's live shipment, if somebody is already carrying this order.
     *
     * <p>Read at the top of every tick, and not as a substitute for the
     * single-winner index — that index is what makes a second shipment
     * unwritable. This closes the other half of the same problem: a worker that
     * won the compare-and-set and died before it could record the fact leaves a
     * plan that still looks unsourced, and a tick that re-decides it walks to the
     * next partner and books a real second courier whose insert then loses. The
     * loser is unwritable; the booking that produced it is not.
     */
    Optional<UUID> assignedShipment(UUID tenantId, UUID planId);

    /** An offer nobody answered. Frees the plan for the next courier or a partner. */
    int expireLapsedOffers(UUID tenantId, UUID planId, Instant now);

    /** Write-once evidence behind a selection. Rows already present are left alone. */
    void recordQuotes(UUID tenantId, UUID planId, List<DeliveryQuote> quotes);

    /**
     * ADR 0014's {@code MANUAL_ACTION_REQUIRED}, as a row an operator can filter.
     *
     * <p>One open row per plan and reason. A sweeper running every minute must not
     * produce sixty rows an hour saying the same thing, and
     * {@code ux_exception_one_open} is what stops it.
     */
    void raiseException(UUID tenantId, UUID brandId, UUID locationId, UUID planId,
            String reasonCode, String detail, Instant now);

    /**
     * @param idempotencyKey the key the partner sees, derived from the plan, the
     *                       binding and the attempt number. Never random: a fresh
     *                       id defeats the provider-side deduplication a retry
     *                       depends on
     * @param quoteId        the quote this selection was made on, or null when the
     *                       partner could not be quoted
     */
    record PartnerAttempt(
            UUID tenantId,
            UUID planId,
            UUID bindingId,
            String idempotencyKey,
            UUID quoteId,
            String decisionReason,
            UUID policyId,
            int policyVersion,
            Instant now) { }

    /**
     * @param expiresAt when the offer lapses. Never null: a courier who is never
     *                  told an offer ended holds an order nobody else can be given
     */
    record InternalOffer(
            UUID tenantId,
            UUID planId,
            UUID courierId,
            String idempotencyKey,
            Instant expiresAt,
            String decisionReason,
            UUID policyId,
            int policyVersion,
            Instant now) { }

    /**
     * The attempt row, whether this call created it or found it.
     *
     * @param fresh false when a replay found the row a previous tick wrote. The
     *              caller must then read {@link #status()} before doing anything
     *              external, because the previous tick may already have booked
     */
    record OpenAttempt(UUID attemptId, int sequenceNumber, AttemptStatus status, boolean fresh) {

        /** Whether the outside world still has to be asked. */
        public boolean needsCall() {
            return status == AttemptStatus.REQUESTED;
        }
    }
}
