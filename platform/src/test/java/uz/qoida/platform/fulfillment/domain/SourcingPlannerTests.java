package uz.qoida.platform.fulfillment.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.fulfillment.api.InternalFleetPort.FleetCandidate;
import uz.qoida.platform.fulfillment.api.ShipmentBookingPort.BookingIntent;
import uz.qoida.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.qoida.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.qoida.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingPlanner;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingProgress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fleet-first decision and, above all, when it stops being fleet-first
 * (ADR 0014).
 *
 * <p>Every test here fixes the clock and states the whole world as arguments,
 * because that is the property the planner is written for: a decision is a pure
 * function of the evidence, so a decision an operator disputes can be replayed
 * exactly.
 */
class SourcingPlannerTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final DeliverySourcingPolicy POLICY = DeliverySourcingPolicy.DEFAULTS;
    private static final Instant CONFIRMED = Instant.parse("2026-08-24T12:00:00Z");

    /** Ready at 14:00, window 14:00–14:15, source at 13:45, latest assignment 14:30. */
    private static final PickupPlan PLAN =
            PickupPlan.forOrder(CONFIRMED, Duration.ofHours(2), TASHKENT, POLICY);

    private static final UUID ALISHER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOBUR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final PartnerOption YANDEX = new PartnerOption(
            UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"), "yandex-delivery", true, true);
    private static final PartnerOption NOOR = new PartnerOption(
            UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"), "noor-delivery", false, true);

    @Test
    @DisplayName("the fleet is offered the order first when somebody is free")
    void fleetGoesFirst() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 800)), List.of(YANDEX),
                SourcingProgress.starting(now), now);

        assertThat(decision).isInstanceOf(SourcingDecision.OfferInternal.class);
        SourcingDecision.OfferInternal offer = (SourcingDecision.OfferInternal) decision;
        assertThat(offer.courierId()).isEqualTo(ALISHER);
        assertThat(offer.reason()).isEqualTo(SourcingDecision.FLEET_AVAILABLE);
        // The courier type's own TTL, capped by the policy ceiling.
        assertThat(offer.expiresAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    @DisplayName("an empty fleet falls back at once rather than spending the window proving it")
    void anEmptyFleetFallsBackImmediately() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(), List.of(YANDEX), SourcingProgress.starting(now), now);

        // Nobody was asked, so nothing was learned by waiting. This is the
        // difference between "the fleet declined" and "the fleet was not there",
        // and only the first is worth spending slack on.
        assertThat(decision).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);
    }

    @Test
    @DisplayName("a fleet that is at capacity is not a fleet that declined")
    void couriersAtCapacityAreNotCandidates() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(new FleetCandidate(ALISHER, 60, 1, 1, 200, 4)),
                List.of(YANDEX), SourcingProgress.starting(now), now);

        assertThat(decision.reason()).isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);
    }

    @Test
    @DisplayName("a live offer is waited on, never overtaken by a partner booking")
    void aLiveOfferIsWaitedOn() {
        Instant now = PLAN.sourceAt();
        SourcingProgress progress = SourcingProgress.starting(now)
                .withOffer(ALISHER, now.plusSeconds(60));

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 800)), List.of(YANDEX),
                progress, now.plusSeconds(30));

        // Falling back here is how a courier accepts an order that has already
        // been given to Yandex, which is the duplicate-courier failure the whole
        // single-winner rule exists to prevent.
        assertThat(decision).isInstanceOf(SourcingDecision.WaitForInternal.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.OFFER_OUTSTANDING);
    }

    @Test
    @DisplayName("a second courier is offered the order before a partner is called")
    void aLapsedOfferGoesToTheNextCourier() {
        Instant now = PLAN.sourceAt();
        SourcingProgress lapsed = SourcingProgress.starting(now)
                .withOffer(ALISHER, now.plusSeconds(60))
                .withoutOffer();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200), courier(BOBUR, 0, 900)),
                List.of(YANDEX), lapsed, now.plusSeconds(61));

        // One round is a single phone in a pocket. Two is the fleet declining.
        assertThat(decision).isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(((SourcingDecision.OfferInternal) decision).courierId()).isEqualTo(BOBUR);
    }

    @Test
    @DisplayName("a courier who was already offered this order is never asked twice")
    void aDeclinedCourierIsNotAskedAgain() {
        Instant now = PLAN.sourceAt();
        SourcingProgress lapsed = SourcingProgress.starting(now)
                .withOffer(ALISHER, now.plusSeconds(60))
                .withoutOffer();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200)), List.of(YANDEX), lapsed, now.plusSeconds(61));

        // The only eligible courier has answered. Asking again costs another
        // ninety seconds of the pickup window to re-learn the same thing.
        assertThat(decision).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.FLEET_DECLINED);
    }

    @Test
    @DisplayName("the fleet lane ends after the configured number of rounds even with couriers left")
    void offerRoundsBoundTheFleetLane() {
        Instant now = PLAN.sourceAt();
        UUID third = UUID.fromString("33333333-3333-3333-3333-333333333333");
        SourcingProgress twoRounds = SourcingProgress.starting(now)
                .withOffer(ALISHER, now.plusSeconds(60)).withoutOffer()
                .withOffer(BOBUR, now.plusSeconds(120)).withoutOffer();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200), courier(BOBUR, 0, 300), courier(third, 0, 400)),
                List.of(YANDEX), twoRounds, now.plusSeconds(180));

        assertThat(decision).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.FLEET_DECLINED);
    }

    @Test
    @DisplayName("the fleet loses the order at the last instant a partner could still make the window")
    void theHandoverDeadlineEndsTheFleetLane() {
        // pickupWindowEnd 14:15 less a fifteen-minute partner lead is 14:00.
        Instant deadline = PLAN.pickupWindowEnd().minusSeconds(POLICY.partnerLeadSeconds());
        SourcingProgress fresh = SourcingProgress.starting(PLAN.sourceAt());

        SourcingDecision justBefore = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200)), List.of(YANDEX), fresh, deadline.minusSeconds(1));
        SourcingDecision atDeadline = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200)), List.of(YANDEX), fresh, deadline);

        assertThat(justBefore).isInstanceOf(SourcingDecision.OfferInternal.class);
        // One second later the partner can no longer reach the branch inside the
        // window. Waiting past this point cannot save the delivery — it spends
        // the commission and arrives late anyway.
        assertThat(atDeadline).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(atDeadline.reason()).isEqualTo(SourcingDecision.FLEET_BUDGET_SPENT);
    }

    @Test
    @DisplayName("an offer never outlives the moment a partner could still have been called")
    void anOfferIsClampedToTheHandoverDeadline() {
        Instant deadline = PLAN.pickupWindowEnd().minusSeconds(POLICY.partnerLeadSeconds());
        Instant now = deadline.minusSeconds(20);

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200)), List.of(YANDEX),
                SourcingProgress.starting(now), now);

        // A sixty-second offer twenty seconds from the deadline would quietly
        // turn the fleet into the only lane.
        assertThat(((SourcingDecision.OfferInternal) decision).expiresAt()).isEqualTo(deadline);
    }

    @Test
    @DisplayName("the emptiest hands win, then the nearest, and an unknown position ranks last")
    void rankingPrefersFreeCouriersThenNearOnes() {
        Instant now = PLAN.sourceAt();
        UUID far = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID unknown = UUID.fromString("55555555-5555-5555-5555-555555555555");

        SourcingDecision loaded = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(new FleetCandidate(ALISHER, 60, 1, 2, 100, 3), courier(BOBUR, 0, 4_000)),
                List.of(YANDEX), SourcingProgress.starting(now), now);

        // Alisher is a hundred metres away and carrying an order; Bobur is four
        // kilometres away and free. Bobur can leave now, Alisher cannot.
        assertThat(((SourcingDecision.OfferInternal) loaded).courierId()).isEqualTo(BOBUR);

        SourcingDecision positioned = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(new FleetCandidate(unknown, 60, 0, 2, null, 0), courier(far, 0, 9_000)),
                List.of(YANDEX), SourcingProgress.starting(now), now);

        // A courier ADR 0045 has no fresh position for is not evidence of being
        // close. Ranking a null as zero would send every order to whoever the
        // telemetry had lost.
        assertThat(((SourcingDecision.OfferInternal) positioned).courierId()).isEqualTo(far);
    }

    @Test
    @DisplayName("a partner that schedules is given the pickup window rather than booked for now")
    void aSchedulingPartnerIsGivenTheWindow() {
        Instant now = PLAN.sourceAt().minusSeconds(1_800);

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.PARTNER_ONLY,
                List.of(), List.of(NOOR), SourcingProgress.starting(now), now);

        SourcingDecision.BookPartner book = (SourcingDecision.BookPartner) decision;
        assertThat(book.intent()).isEqualTo(BookingIntent.BOOK_FOR_PICKUP_WINDOW);
        assertThat(book.requestedPickupAt()).isEqualTo(PLAN.pickupWindowStart());
        assertThat(book.reason()).isEqualTo(SourcingDecision.PARTNER_ONLY_MODE);
    }

    @Test
    @DisplayName("close to the window the partner is booked for now, not for a time already passing")
    void nearTheWindowThePartnerIsBookedNow() {
        Instant now = PLAN.pickupWindowStart().minusSeconds(60);

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.PARTNER_ONLY,
                List.of(), List.of(NOOR), SourcingProgress.starting(now), now);

        SourcingDecision.BookPartner book = (SourcingDecision.BookPartner) decision;
        assertThat(book.intent()).isEqualTo(BookingIntent.BOOK_NOW);
        assertThat(book.requestedPickupAt()).isNull();
    }

    @Test
    @DisplayName("the planner never speculatively holds while no attempt table can record one")
    void noSpeculativeHoldIsEverProduced() {
        Instant now = PLAN.sourceAt();

        // Yandex supports holds and ADR 0014 permits taking one in parallel. It
        // also requires every losing hold to be explicitly cancelled, and there
        // is no assignment_attempts row to find an abandoned one in.
        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(), List.of(YANDEX), SourcingProgress.starting(now), now);

        assertThat(((SourcingDecision.BookPartner) decision).intent())
                .isNotEqualTo(BookingIntent.HOLD);
    }

    @Test
    @DisplayName("an unreconciled uncertain attempt stops sourcing instead of trying the next partner")
    void anUncertainAttemptBlocksTheFallback() {
        Instant now = PLAN.sourceAt();
        SourcingProgress uncertain = SourcingProgress.starting(now)
                .withPartnerAttempt(YANDEX.bindingId(), true);

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200)), List.of(YANDEX, NOOR), uncertain, now);

        // ADR 0014: do not book a fallback while the first provider may have
        // accepted. The route already queried and could not settle it, so the
        // only safe move left is a human.
        assertThat(decision).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.AWAITING_RECONCILIATION);
    }

    @Test
    @DisplayName("a refused partner is stepped past, and running out of them escalates")
    void exhaustedPartnersEscalate() {
        Instant now = PLAN.sourceAt();
        SourcingProgress refused = SourcingProgress.starting(now)
                .withPartnerAttempt(YANDEX.bindingId(), false);

        SourcingDecision next = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.PARTNER_ONLY,
                List.of(), List.of(YANDEX, NOOR), refused, now);
        assertThat(((SourcingDecision.BookPartner) next).partner()).isEqualTo(NOOR);

        SourcingDecision none = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.PARTNER_ONLY,
                List.of(), List.of(YANDEX),
                refused, now);
        assertThat(none).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(none.reason()).isEqualTo(SourcingDecision.PARTNERS_EXHAUSTED);
    }

    @Test
    @DisplayName("a branch with no delivery binding and no fleet is an operations exception")
    void noPartnerAndNoFleetEscalates() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(), List.of(), SourcingProgress.starting(now), now);

        assertThat(decision).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.NO_PARTNER_CONFIGURED);
    }

    @Test
    @DisplayName("a fleet-only plan never calls a partner and escalates instead")
    void fleetOnlyNeverCallsAPartner() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_ONLY,
                List.of(), List.of(YANDEX), SourcingProgress.starting(now), now);

        assertThat(decision).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);
    }

    @Test
    @DisplayName("a fleet-only plan keeps the whole assignment window instead of a handover deadline")
    void fleetOnlyIsNotBoundedByAPartnerLead() {
        // Past the moment a partner could still have been called, but there is
        // no partner to protect, so the fleet is still the answer.
        Instant afterHandover = PLAN.pickupWindowEnd().minusSeconds(60);

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_ONLY,
                List.of(courier(ALISHER, 0, 200)), List.of(),
                SourcingProgress.starting(PLAN.sourceAt()), afterHandover);

        assertThat(decision).isInstanceOf(SourcingDecision.OfferInternal.class);
    }

    @Test
    @DisplayName("past the latest assignment instant nothing is booked and a human is told")
    void anUnreachablePromiseEscalates() {
        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.FLEET_FIRST,
                List.of(courier(ALISHER, 0, 200)), List.of(YANDEX),
                SourcingProgress.starting(PLAN.sourceAt()), PLAN.latestAssignmentAt());

        // The confirmed order is retained. A customer whose food is cooking is
        // not the right person to pay for the fleet being empty.
        assertThat(decision).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.PROMISE_UNREACHABLE);
    }

    @Test
    @DisplayName("a manual plan produces a plan and a window and then stops")
    void manualModeStops() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision = SourcingPlanner.decide(PLAN, POLICY, SourcingMode.MANUAL,
                List.of(courier(ALISHER, 0, 200)), List.of(YANDEX),
                SourcingProgress.starting(now), now);

        assertThat(decision.reason()).isEqualTo(SourcingDecision.MANUAL_MODE);
    }

    private static FleetCandidate courier(UUID id, int activeAssignments, int metresFromBranch) {
        return new FleetCandidate(id, 60, activeAssignments, 2, metresFromBranch, 3);
    }
}
