package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingIntent;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;

/**
 * What sourcing decided to do next, and why (ADR 0014 "Provider selection").
 *
 * <p>A decision, not an action. ADR 0014 is explicit that the selection service
 * returns a decision and that Camel performs the call — so this is a value that
 * can be logged, stored as evidence, asserted on in a test, and replayed,
 * without anything having happened to a courier.
 *
 * <p>Sealed, so that adding a fifth thing sourcing can do fails every exhaustive
 * switch in the module rather than falling through one of them silently.
 */
public sealed interface SourcingDecision {

    /** Always present, always a stable code, and never rendered to a customer. */
    String reason();

    /**
     * Offer this order to one in-house courier until {@code expiresAt}.
     *
     * <p>Executing this needs the {@code fulfillment.assignment_attempts} row
     * whose unique index is the single-winner guarantee, so the caller can
     * decide and record but cannot yet durably offer.
     */
    record OfferInternal(UUID courierId, Instant expiresAt, String reason) implements SourcingDecision {}

    /**
     * Nothing to do until {@code retryAt}, because an offer is still live.
     *
     * <p>A distinct answer from "keep the fleet lane open": the scheduler sleeps
     * on this one, and a caller that treats it as a fallback trigger turns every
     * in-flight offer into a partner booking.
     */
    record WaitForInternal(UUID courierId, Instant retryAt, String reason) implements SourcingDecision {}

    /**
     * Book with this partner.
     *
     * @param intent {@link BookingIntent#HOLD} only where the partner supports
     *               holds. On a partner whose create is live — verified for Noor
     *               — this is always a booking, never a speculative one
     * @param requestedPickupAt the scheduled pickup instant for
     *               {@link BookingIntent#BOOK_FOR_PICKUP_WINDOW}, or null for a
     *               booking made now
     */
    record BookPartner(
            PartnerOption partner,
            BookingIntent intent,
            @Nullable Instant requestedPickupAt,
            String reason) implements SourcingDecision {}

    /**
     * No automated path remains. ADR 0014: create {@code MANUAL_ACTION_REQUIRED},
     * notify Operations, and retain the confirmed order.
     *
     * <p>The confirmed order is never cancelled by sourcing. A customer whose
     * food is cooking is not the right person to pay for a fleet being empty.
     */
    record EscalateToOperations(String reason) implements SourcingDecision {}

    // Reason codes. Strings rather than an enum because they travel into events,
    // metrics tags and an operations screen, where a value that survives a
    // refactor of this file is worth more than an exhaustive switch.

    /** The fleet has somebody free and the pickup window is not under threat. */
    String FLEET_AVAILABLE = "FLEET_AVAILABLE";

    /** An offer is live and has not lapsed. */
    String OFFER_OUTSTANDING = "OFFER_OUTSTANDING";

    /** Nobody on shift, nobody with capacity, or nobody within the distance band. */
    String NO_INTERNAL_CANDIDATE = "NO_INTERNAL_CANDIDATE";

    /** Every eligible courier has been offered this order and none took it. */
    String FLEET_DECLINED = "FLEET_DECLINED";

    /**
     * The in-house lane ran out of time. Waiting longer would cost the pickup
     * window rather than the commission.
     */
    String FLEET_BUDGET_SPENT = "FLEET_BUDGET_SPENT";

    /** The tenant configured partners only. */
    String PARTNER_ONLY_MODE = "PARTNER_ONLY_MODE";

    /** No delivery binding exists for this branch at all. */
    String NO_PARTNER_CONFIGURED = "NO_PARTNER_CONFIGURED";

    /** Every configured partner has already refused this plan. */
    String PARTNERS_EXHAUSTED = "PARTNERS_EXHAUSTED";

    /**
     * A partner attempt ended UNCERTAIN. ADR 0014 forbids booking a fallback
     * while the first provider may have accepted, so this stops rather than
     * trying the next one.
     */
    String AWAITING_RECONCILIATION = "AWAITING_RECONCILIATION";

    /** Past {@code latest_assignment_at}; no assignment can still meet the promise. */
    String PROMISE_UNREACHABLE = "PROMISE_UNREACHABLE";

    /** The plan is in MANUAL mode and operations owns the assignment. */
    String MANUAL_MODE = "MANUAL_MODE";
}
