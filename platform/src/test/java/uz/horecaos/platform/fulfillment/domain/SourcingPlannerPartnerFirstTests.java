package uz.horecaos.platform.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort.FleetCandidate;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Grouping;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingPlanner;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;

/**
 * {@link SourcingMode#PARTNER_FIRST} and the grouping bias (ADR 0142 Decisions 6 and 9).
 *
 * <p>Beside {@code SourcingPlannerTests}, which pins the four modes that existed before and is not edited:
 * "the regression fixture over the four existing modes must not move". Every test here states the whole
 * world as arguments with a fixed clock.
 */
class SourcingPlannerPartnerFirstTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final DeliverySourcingPolicy POLICY = DeliverySourcingPolicy.DEFAULTS;
    private static final Instant CONFIRMED = Instant.parse("2026-08-24T12:00:00Z");

    /** Ready at 14:00, window 14:00-14:15, source at 13:45, latest assignment 14:30. */
    private static final PickupPlan PLAN = PickupPlan.forOrder(CONFIRMED, Duration.ofHours(2), TASHKENT, POLICY);

    /** The last instant a partner could still make the window: 14:15 less its 15-minute lead. */
    private static final Instant HANDOVER = PLAN.pickupWindowEnd().minusSeconds(POLICY.partnerLeadSeconds());

    private static final UUID ALISHER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOBUR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final PartnerOption YANDEX =
            new PartnerOption(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"), "yandex-delivery", true, true);
    private static final PartnerOption NOOR =
            new PartnerOption(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"), "noor-delivery", false, true);

    // ---------------------------------------------------------- the partner lane

    @Test
    @DisplayName("with a partner bound and couriers free, the partner is booked first and no courier is asked")
    void thePartnerIsBookedBeforeAnyCourier() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision =
                decide(List.of(courier(ALISHER, 0, 200)), List.of(YANDEX, NOOR), progress(now), now);

        assertThat(decision).isInstanceOf(SourcingDecision.BookPartner.class);
        SourcingDecision.BookPartner book = (SourcingDecision.BookPartner) decision;
        assertThat(book.partner()).isEqualTo(YANDEX);
        assertThat(book.reason()).isEqualTo(SourcingDecision.PARTNER_FIRST_MODE);
    }

    @Test
    @DisplayName("a refused partner is stepped past to the next one before any courier is considered")
    void aRefusedPartnerMovesToTheNext() {
        Instant now = PLAN.sourceAt();
        SourcingProgress refused = progress(now).withPartnerAttempt(YANDEX.bindingId(), false);

        SourcingDecision decision = decide(List.of(courier(ALISHER, 0, 200)), List.of(YANDEX, NOOR), refused, now);

        assertThat(decision).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(((SourcingDecision.BookPartner) decision).partner()).isEqualTo(NOOR);
    }

    @Test
    @DisplayName(
            "when every partner has refused, a courier is offered and the journal says the partners were exhausted")
    void exhaustedPartnersFallBackToTheFleet() {
        Instant now = PLAN.sourceAt();
        SourcingProgress allRefused =
                progress(now).withPartnerAttempt(YANDEX.bindingId(), false).withPartnerAttempt(NOOR.bindingId(), false);

        SourcingDecision decision = decide(List.of(courier(ALISHER, 0, 200)), List.of(YANDEX, NOOR), allRefused, now);

        assertThat(decision).isInstanceOf(SourcingDecision.OfferInternal.class);
        SourcingDecision.OfferInternal offer = (SourcingDecision.OfferInternal) decision;
        assertThat(offer.courierId()).isEqualTo(ALISHER);
        assertThat(offer.reason())
                .as("the attempt journal says why a courier was asked")
                .isEqualTo(SourcingDecision.PARTNERS_EXHAUSTED);
    }

    @Test
    @DisplayName("with no partner bound at all the fleet is offered the plan, for that reason")
    void noPartnerBoundOffersTheFleet() {
        Instant now = PLAN.sourceAt();

        SourcingDecision decision = decide(List.of(courier(ALISHER, 0, 200)), List.of(), progress(now), now);

        assertThat(decision).isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.NO_PARTNER_CONFIGURED);
    }

    @Test
    @DisplayName(
            "an uncertain partner attempt escalates before either lane runs: a courier is never the fallback to a booking that may exist")
    void anUncertainAttemptNeverFallsThroughToACourier() {
        Instant now = PLAN.sourceAt();
        SourcingProgress uncertain = progress(now).withPartnerAttempt(YANDEX.bindingId(), true);

        SourcingDecision decision = decide(List.of(courier(ALISHER, 0, 200)), List.of(YANDEX, NOOR), uncertain, now);

        // Two couriers on one order is what ADR 0014's rule exists to prevent.
        assertThat(decision).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.AWAITING_RECONCILIATION);
    }

    // ------------------------------------------------------- the fleet lane

    @Test
    @DisplayName("past the instant a partner could still make the window, the fleet lane still opens")
    void theFleetLaneOpensPastTheHandoverDeadline() {
        // The case a handoverDeadline keyed on "the mode uses partners" gets wrong: PARTNER_FIRST uses
        // partners, but its fleet lane has no partner behind it to protect, so its deadline is
        // latest_assignment_at. Keyed the other way it would open already past its deadline and refuse
        // at once with FLEET_BUDGET_SPENT.
        Instant now = HANDOVER.plusSeconds(60);
        SourcingProgress allRefused = progress(PLAN.sourceAt())
                .withPartnerAttempt(YANDEX.bindingId(), false)
                .withPartnerAttempt(NOOR.bindingId(), false);

        SourcingDecision decision = decide(List.of(courier(ALISHER, 0, 200)), List.of(YANDEX, NOOR), allRefused, now);

        assertThat(decision)
                .as("a courier is offered, not FLEET_BUDGET_SPENT")
                .isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.PARTNERS_EXHAUSTED);
    }

    @Test
    @DisplayName("the offer is clamped to the last assignment instant, not to a handover deadline")
    void theOfferIsClampedToTheLastAssignment() {
        Instant now = PLAN.latestAssignmentAt().minusSeconds(20);
        SourcingProgress noPartners = progress(PLAN.sourceAt());

        SourcingDecision decision = decide(List.of(courier(ALISHER, 0, 200)), List.of(), noPartners, now);

        assertThat(decision).isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(((SourcingDecision.OfferInternal) decision).expiresAt())
                .as("a sixty-second offer twenty seconds from the last assignment instant ends there")
                .isEqualTo(PLAN.latestAssignmentAt());
    }

    @Test
    @DisplayName("at the last assignment instant nothing is booked or offered and a human is told")
    void theFleetLaneEndsAtTheLastAssignment() {
        SourcingDecision decision = decide(
                List.of(courier(ALISHER, 0, 200)), List.of(), progress(PLAN.sourceAt()), PLAN.latestAssignmentAt());

        assertThat(decision).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.PROMISE_UNREACHABLE);
    }

    @Test
    @DisplayName("partners and fleet both exhausted escalate, naming the fleet's answer")
    void bothLanesExhaustedEscalate() {
        Instant now = PLAN.sourceAt();
        SourcingProgress spent = progress(now)
                .withPartnerAttempt(YANDEX.bindingId(), false)
                .withOffer(ALISHER, now.plusSeconds(60))
                .withoutOffer();

        SourcingDecision declined =
                decide(List.of(courier(ALISHER, 0, 200)), List.of(YANDEX), spent, now.plusSeconds(61));
        assertThat(declined).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(declined.reason()).isEqualTo(SourcingDecision.FLEET_DECLINED);

        SourcingDecision nobody =
                decide(List.of(), List.of(YANDEX), progress(now).withPartnerAttempt(YANDEX.bindingId(), false), now);
        assertThat(nobody).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(nobody.reason()).isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);
    }

    @Test
    @DisplayName("a live courier offer is waited on, never overtaken")
    void aLiveOfferIsWaitedOn() {
        Instant now = PLAN.sourceAt();
        SourcingProgress offered =
                progress(now).withPartnerAttempt(YANDEX.bindingId(), false).withOffer(ALISHER, now.plusSeconds(60));

        SourcingDecision decision =
                decide(List.of(courier(ALISHER, 0, 200)), List.of(YANDEX, NOOR), offered, now.plusSeconds(30));

        assertThat(decision).isInstanceOf(SourcingDecision.WaitForInternal.class);
    }

    @Test
    @DisplayName("PARTNER_ONLY is unchanged: exhausted partners escalate and no courier is asked")
    void partnerOnlyStillNeverReachesTheFleet() {
        Instant now = PLAN.sourceAt();
        SourcingProgress refused = progress(now).withPartnerAttempt(YANDEX.bindingId(), false);

        SourcingDecision decision = SourcingPlanner.decide(
                PLAN,
                POLICY,
                SourcingMode.PARTNER_ONLY,
                List.of(courier(ALISHER, 0, 200)),
                List.of(YANDEX),
                refused,
                now);

        assertThat(decision).isInstanceOf(SourcingDecision.EscalateToOperations.class);
        assertThat(decision.reason()).isEqualTo(SourcingDecision.PARTNERS_EXHAUSTED);
    }

    @Test
    @DisplayName("the mode's lane order is a property of the mode, not of the planner's branches")
    void laneOrderIsPartOfTheMode() {
        assertThat(SourcingMode.PARTNER_FIRST.partnerLaneFirst()).isTrue();
        assertThat(SourcingMode.PARTNER_FIRST.usesFleet()).isTrue();
        assertThat(SourcingMode.PARTNER_FIRST.usesPartners()).isTrue();
        assertThat(SourcingMode.PARTNER_FIRST.partnerLaneFollowsFleet()).isFalse();
        assertThat(SourcingMode.FLEET_FIRST.partnerLaneFollowsFleet()).isTrue();
        assertThat(SourcingMode.FLEET_FIRST.partnerLaneFirst()).isFalse();
        assertThat(SourcingMode.PARTNER_ONLY.partnerLaneFollowsFleet()).isFalse();
        assertThat(SourcingMode.FLEET_ONLY.partnerLaneFollowsFleet()).isFalse();
    }

    // -------------------------------------------------------------- grouping

    @Test
    @DisplayName("a courier already carrying a nearby un-picked-up order outranks an emptier one inside the radius")
    void aGroupableCourierOutranksAnEmptierOne() {
        Instant now = PLAN.sourceAt();
        Grouping grouping = new Grouping(800, 3, 120);
        // Alisher carries one order whose drop-off is 400 m from this one; Bobur is free and nearer the branch.
        FleetCandidate carrying = new FleetCandidate(ALISHER, 60, 1, 3, 900, 5, 400);
        FleetCandidate free = new FleetCandidate(BOBUR, 60, 0, 3, 100, 5, null);

        SourcingDecision grouped = SourcingPlanner.decide(
                PLAN,
                POLICY,
                SourcingMode.FLEET_FIRST,
                List.of(carrying, free),
                List.of(YANDEX),
                progress(now),
                now,
                grouping);
        SourcingDecision ungrouped = SourcingPlanner.decide(
                PLAN, POLICY, SourcingMode.FLEET_FIRST, List.of(carrying, free), List.of(YANDEX), progress(now), now);

        assertThat(((SourcingDecision.OfferInternal) grouped).courierId()).isEqualTo(ALISHER);
        assertThat(((SourcingDecision.OfferInternal) ungrouped).courierId())
                .as("without a rule enabling grouping the emptiest hands still win")
                .isEqualTo(BOBUR);
    }

    @Test
    @DisplayName("outside the merge radius there is no bias, and a courier at the run's ceiling is not groupable")
    void noBiasOutsideTheRadiusOrAtTheCeiling() {
        Instant now = PLAN.sourceAt();
        Grouping grouping = new Grouping(800, 2, 120);
        FleetCandidate tooFar = new FleetCandidate(ALISHER, 60, 1, 3, 900, 5, 801);
        FleetCandidate atRunCeiling = new FleetCandidate(BOBUR, 60, 2, 3, 100, 5, 100);
        FleetCandidate free =
                new FleetCandidate(UUID.fromString("33333333-3333-3333-3333-333333333333"), 60, 0, 3, 500, 5, null);

        SourcingDecision decision = SourcingPlanner.decide(
                PLAN,
                POLICY,
                SourcingMode.FLEET_FIRST,
                List.of(tooFar, atRunCeiling, free),
                List.of(YANDEX),
                progress(now),
                now,
                grouping);

        assertThat(((SourcingDecision.OfferInternal) decision).courierId())
                .as("801 m is outside an 800 m radius, and a courier already on two orders is at a run of two")
                .isEqualTo(free.courierId());
    }

    @Test
    @DisplayName("the nearest groupable drop-off is asked first among groupable couriers")
    void theNearestGroupableDropOffIsAskedFirst() {
        Instant now = PLAN.sourceAt();
        Grouping grouping = new Grouping(800, 4, 120);
        FleetCandidate near = new FleetCandidate(ALISHER, 60, 1, 4, 5_000, 5, 150);
        FleetCandidate farther = new FleetCandidate(BOBUR, 60, 1, 4, 100, 5, 700);

        SourcingDecision decision = SourcingPlanner.decide(
                PLAN,
                POLICY,
                SourcingMode.FLEET_FIRST,
                List.of(farther, near),
                List.of(),
                progress(now),
                now,
                grouping);

        assertThat(((SourcingDecision.OfferInternal) decision).courierId()).isEqualTo(ALISHER);
    }

    @Test
    @DisplayName(
            "grouping is a bias on the fleet lane under PARTNER_FIRST too, and changes nothing about the partner lane")
    void groupingBiasesThePartnerFirstFleetLane() {
        Instant now = PLAN.sourceAt();
        Grouping grouping = new Grouping(800, 3, 120);
        FleetCandidate carrying = new FleetCandidate(ALISHER, 60, 1, 3, 900, 5, 400);
        FleetCandidate free = new FleetCandidate(BOBUR, 60, 0, 3, 100, 5, null);

        SourcingDecision partnerLane = SourcingPlanner.decide(
                PLAN,
                POLICY,
                SourcingMode.PARTNER_FIRST,
                List.of(carrying, free),
                List.of(YANDEX),
                progress(now),
                now,
                grouping);
        assertThat(partnerLane).isInstanceOf(SourcingDecision.BookPartner.class);

        SourcingDecision fleetLane = SourcingPlanner.decide(
                PLAN,
                POLICY,
                SourcingMode.PARTNER_FIRST,
                List.of(carrying, free),
                List.of(),
                progress(now),
                now,
                grouping);
        assertThat(((SourcingDecision.OfferInternal) fleetLane).courierId()).isEqualTo(ALISHER);
    }

    // ---------------------------------------------------------------- fixtures

    private static SourcingDecision decide(
            List<FleetCandidate> candidates, List<PartnerOption> partners, SourcingProgress progress, Instant now) {
        return SourcingPlanner.decide(PLAN, POLICY, SourcingMode.PARTNER_FIRST, candidates, partners, progress, now);
    }

    private static SourcingProgress progress(Instant startedAt) {
        return SourcingProgress.starting(startedAt);
    }

    private static FleetCandidate courier(UUID id, int activeAssignments, int metresFromBranch) {
        return new FleetCandidate(id, 60, activeAssignments, 2, metresFromBranch, 3);
    }
}
