package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort.FleetCandidate;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingIntent;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;

/**
 * In-house first, a partner when the fleet cannot take it (ADR 0014).
 *
 * <p>A pure function of its arguments: no clock, no database, no port. That is
 * what makes ADR 0014's requirement — a selection "explainable and reproducible
 * from stored evidence" — achievable, because the evidence is exactly this
 * method's arguments.
 *
 * <h2>How long the fleet is given before a partner is called</h2>
 *
 * <p>This is the judgement the rest of the class exists to serve, and the two
 * failure modes pull in opposite directions. Falling back too early pays a
 * partner commission on an order one of our own couriers would have taken thirty
 * seconds later. Falling back too late means the partner's courier is still
 * being dispatched while the food sits under a lamp — and by then the commission
 * has been spent anyway, on a late delivery.
 *
 * <p>So the fleet's time is not a countdown configured in seconds. It is derived
 * backwards from the promise, and bounded by two independent stops:
 *
 * <ol>
 *   <li><b>The handover deadline.</b> A partner needs
 *       {@code partnerLeadSeconds} between accepting a booking and its courier
 *       reaching the branch. The last instant at which handing over still gets
 *       somebody there inside the pickup window is therefore
 *       {@code pickupWindowEnd - partnerLeadSeconds}. Waiting past it cannot
 *       save the delivery: it can only spend the commission <em>and</em> arrive
 *       late. This is a hard stop and it is why the wait is not a constant —
 *       an order with a two-hour preparation gives the fleet an hour of slack,
 *       and a twenty-minute order gives it almost none, correctly.</li>
 *   <li><b>The offer rounds.</b> Time alone is the wrong measure when the fleet
 *       is empty. An order offered to nobody has not been refused by the fleet,
 *       it has been delayed by it, so a plan with no eligible courier falls back
 *       immediately rather than burning slack proving something already known.
 *       Conversely a plan whose only courier missed a notification deserves a
 *       second name before it goes outside — one round is a single phone in a
 *       pocket, two is the fleet actually declining. Past
 *       {@code offerRounds} distinct couriers, the fleet has answered.</li>
 * </ol>
 *
 * <p>Whichever stop arrives first ends the lane. Nothing here waits for an
 * expired offer to be re-offered to the same courier: a decline is information,
 * and asking again costs another offer TTL to learn what is already known.
 *
 * <h2>What this deliberately does not do yet</h2>
 *
 * <p>It never produces {@link BookingIntent#HOLD}. Yandex's created-but-unaccepted
 * claim would make a very good insurance policy — held while the fleet still has
 * time, confirmed if the fleet declines, cancelled if it does not — and ADR 0014
 * explicitly permits it. It also says that a hold which does not win "must be
 * explicitly cancelled and its cancellation confirmed; an abandoned hold is an
 * operational exception, not a no-op". There is no
 * {@code fulfillment.assignment_attempts} row to record a hold in, so nothing
 * could find one that was abandoned. A hold nobody can reconcile is worse than
 * the commission it saves, so the intent exists on the port and this planner
 * will not emit it until the table does.
 */
public final class SourcingPlanner {

    /**
     * Emptiest hands, then nearest, then fewest deliveries already done, then id.
     *
     * <p>Load before distance on purpose. A courier already carrying two orders
     * is not going to reach the branch sooner for being closer to it — he has to
     * finish what he has first — and preferring him also concentrates the work
     * on whoever happens to live near the branch, which is the fairness
     * complaint ADR 0014 lists as a scoring input.
     *
     * <p>An unknown position ranks last rather than nearest. ADR 0045 stops
     * collecting a courier's position the moment they go on break or their
     * session goes stale, so "no position" means "we do not know", and treating
     * an absent coordinate as a zero distance would send every order to whoever
     * the telemetry had lost.
     */
    private static final Comparator<FleetCandidate> RANKING = Comparator.comparingInt(FleetCandidate::activeAssignments)
            .thenComparing(FleetCandidate::metresFromBranch, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(FleetCandidate::deliveriesThisShift)
            .thenComparing(FleetCandidate::courierId);

    private SourcingPlanner() {}

    /**
     * @param candidates couriers ADR 0042's dispatch gate has already allowed.
     *                   This method filters for capacity and for who has already
     *                   been asked; it does not re-decide eligibility, which
     *                   belongs to the module that owns shifts
     * @param partners   the branch's delivery bindings, narrowest first
     */
    public static SourcingDecision decide(
            PickupPlan plan,
            DeliverySourcingPolicy policy,
            SourcingMode mode,
            List<FleetCandidate> candidates,
            List<PartnerOption> partners,
            SourcingProgress progress,
            Instant now) {

        if (!now.isBefore(plan.latestAssignmentAt())) {
            // The promise is already unreachable, so every remaining automated
            // move books a courier for a delivery that is late whatever happens.
            // ADR 0014: retain the confirmed order and send it to a human.
            return new SourcingDecision.EscalateToOperations(SourcingDecision.PROMISE_UNREACHABLE);
        }
        if (mode == SourcingMode.MANUAL) {
            return new SourcingDecision.EscalateToOperations(SourcingDecision.MANUAL_MODE);
        }
        if (progress.uncertainAttempt()) {
            // The route already tried to reconcile by query — that is what
            // delivery.reconcile.v1 is for — so an uncertain attempt arriving
            // here is one the query could not settle either. Trying the next
            // partner now is precisely how a plan ends up with two couriers.
            return new SourcingDecision.EscalateToOperations(SourcingDecision.AWAITING_RECONCILIATION);
        }
        UUID outstandingOffer = progress.outstandingOffer();
        Instant offerExpiresAt = progress.offerExpiresAt();
        // Inlined rather than delegated to SourcingProgress.hasLiveOffer(now): the
        // pairing invariant that method enforces is real, but it is enforced in a
        // different method, and a null check written there does not narrow these
        // two accessors here.
        if (outstandingOffer != null && offerExpiresAt != null && now.isBefore(offerExpiresAt)) {
            return new SourcingDecision.WaitForInternal(
                    outstandingOffer, offerExpiresAt, SourcingDecision.OFFER_OUTSTANDING);
        }

        Instant handoverDeadline = handoverDeadline(plan, policy, mode);
        String fleetReason = null;

        if (mode.usesFleet()) {
            Optional<FleetCandidate> next = candidates.stream()
                    .filter(FleetCandidate::hasCapacity)
                    .filter(candidate -> !progress.offeredCouriers().contains(candidate.courierId()))
                    .min(RANKING);

            fleetReason = fleetRefusal(candidates, next, progress, policy, now, handoverDeadline);
            if (fleetReason == null) {
                FleetCandidate courier = next.orElseThrow();
                int ttl = Math.min(courier.offerTtlSeconds(), policy.maxOfferSeconds());
                Instant expiry = now.plusSeconds(ttl);
                // Clamped so the offer cannot outlive the moment a partner could
                // still have been called. An offer expiring after the handover
                // deadline is a fleet lane that has quietly become the only lane.
                Instant clamped = expiry.isAfter(handoverDeadline) ? handoverDeadline : expiry;
                return new SourcingDecision.OfferInternal(
                        courier.courierId(), clamped, SourcingDecision.FLEET_AVAILABLE);
            }
        }

        if (!mode.usesPartners()) {
            return new SourcingDecision.EscalateToOperations(
                    fleetReason == null ? SourcingDecision.NO_INTERNAL_CANDIDATE : fleetReason);
        }

        Optional<PartnerOption> partner = partners.stream()
                .filter(option -> !progress.attemptedPartners().contains(option.bindingId()))
                .findFirst();
        if (partner.isEmpty()) {
            return new SourcingDecision.EscalateToOperations(
                    partners.isEmpty() ? SourcingDecision.NO_PARTNER_CONFIGURED : SourcingDecision.PARTNERS_EXHAUSTED);
        }

        String reason;
        if (mode == SourcingMode.PARTNER_ONLY) {
            reason = SourcingDecision.PARTNER_ONLY_MODE;
        } else if (fleetReason != null) {
            reason = fleetReason;
        } else {
            // Unreachable: the only other mode that falls through to here is
            // FLEET_FIRST (FLEET_ONLY and MANUAL both returned above), and
            // fleetReason is null only inside the branch above that already
            // returned an OfferInternal decision. Stated rather than assumed, so
            // a future mode added to usesFleet()/usesPartners() fails loudly
            // instead of silently booking a partner for no recorded reason.
            throw new IllegalStateException(
                    "Reached partner booking with neither a fleet refusal reason nor partner-only mode");
        }
        return bookWith(partner.get(), plan, policy, now, reason);
    }

    /**
     * Why the fleet lane is over, or null while it is still running.
     *
     * <p>The three reasons are kept apart because they send an operator to three
     * different places: nobody on shift is a rota problem, everybody declining
     * is a rate-card problem, and running out of clock is a preparation-estimate
     * problem.
     */
    private static @Nullable String fleetRefusal(
            List<FleetCandidate> candidates,
            Optional<FleetCandidate> next,
            SourcingProgress progress,
            DeliverySourcingPolicy policy,
            Instant now,
            Instant handoverDeadline) {

        if (candidates.stream().noneMatch(FleetCandidate::hasCapacity)) {
            // Nobody could be asked. Not a refusal by the fleet, and spending
            // any of the window on it would be delay that buys no information.
            return SourcingDecision.NO_INTERNAL_CANDIDATE;
        }
        if (!now.isBefore(handoverDeadline)) {
            return SourcingDecision.FLEET_BUDGET_SPENT;
        }
        if (progress.offeredCouriers().size() >= policy.offerRounds()) {
            return SourcingDecision.FLEET_DECLINED;
        }
        if (next.isEmpty()) {
            // Everyone eligible has already been asked, with rounds to spare.
            // Asking the same courier twice inside one run costs another offer
            // TTL to re-learn an answer already given.
            return SourcingDecision.FLEET_DECLINED;
        }
        return null;
    }

    /**
     * The last instant at which handing to a partner still meets the window.
     *
     * <p>For a fleet-only plan there is no partner to protect, so the fleet may
     * use the whole assignment window; the stop that ends the lane there is the
     * offer rounds and, ultimately, {@code latest_assignment_at}.
     */
    private static Instant handoverDeadline(PickupPlan plan, DeliverySourcingPolicy policy, SourcingMode mode) {

        if (!mode.usesPartners()) {
            return plan.latestAssignmentAt();
        }
        Instant deadline = plan.pickupWindowEnd().minusSeconds(policy.partnerLeadSeconds());
        // A short-preparation order can put the deadline before the order was
        // even confirmed. That is not a reason to give the fleet negative time —
        // it is a reason to give it none, which is what returning the earlier of
        // the two does, while still letting the plan be sourced at all.
        return deadline.isBefore(plan.confirmedAt()) ? plan.confirmedAt() : deadline;
    }

    private static SourcingDecision bookWith(
            PartnerOption partner, PickupPlan plan, DeliverySourcingPolicy policy, Instant now, String reason) {

        Instant earliestUseful = now.plusSeconds(policy.partnerLeadSeconds());
        boolean aheadOfWindow = earliestUseful.isBefore(plan.pickupWindowStart());

        if (partner.supportsScheduling() && aheadOfWindow) {
            // Both verified partners take a future pickup time, and giving them
            // one is the difference between a courier waiting unpaid at the
            // counter and one arriving as the bag is sealed.
            return new SourcingDecision.BookPartner(
                    partner, BookingIntent.BOOK_FOR_PICKUP_WINDOW, plan.pickupWindowStart(), reason);
        }
        return new SourcingDecision.BookPartner(partner, BookingIntent.BOOK_NOW, null, reason);
    }
}
